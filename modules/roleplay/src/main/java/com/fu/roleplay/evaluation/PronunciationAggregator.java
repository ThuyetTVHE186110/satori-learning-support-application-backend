package com.fu.roleplay.evaluation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fu.pronunciation.dto.AssessmentResult;
import com.fu.roleplay.entity.MessagePronunciation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Aggregates per-message pronunciation records into a session-level summary with
 * actionable, tier-prioritised weak-item lists.
 *
 * <p>Per {@code PRONUNCIATION_FEEDBACK_PLAN.md}:
 * <ul>
 *   <li>Threshold escalation — surface critical (&lt; 60) items if any exist; else
 *       fall back to marginal (&lt; 80). Never silent unless truly clean.</li>
 *   <li>Impact-ordered, NOT top-N capped. Full list returned; consumer prompt
 *       can cluster long lists by phoneme.</li>
 *   <li>Tier weighting per Levis intelligibility principle: mora/duration outranks
 *       L1-difficult segments, which outranks untiered allophonic deviations.</li>
 * </ul>
 *
 * <p>Word and phoneme thresholds differ: phoneme alignment is noisier (Azure SDK
 * issue #1917) so we use a tighter cutoff to avoid false positives.
 */
@Component
@Slf4j
public class PronunciationAggregator {

    /** Word accuracy below this is "critical" — always surface. */
    private static final double WORD_CRITICAL = 60.0;
    /** Word accuracy below this is "marginal" — surface only when no critical items exist. */
    private static final double WORD_MARGINAL = 80.0;

    /** Phoneme accuracy below this is critical (tighter than word — phoneme alignment noisy). */
    private static final double PHONEME_CRITICAL = 50.0;
    /** Phoneme accuracy below this is marginal. */
    private static final double PHONEME_MARGINAL = 70.0;

    /** Session-level "clean" overall threshold for the cleanSession flag. */
    private static final double CLEAN_OVERALL = 80.0;

    private static final TypeReference<List<AssessmentResult.WordAssessment>> WORD_LIST_TYPE =
            new TypeReference<>() {};

    private final ObjectMapper objectMapper;

    public PronunciationAggregator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public PronunciationSummary aggregate(List<MessagePronunciation> messages) {
        if (messages == null || messages.isEmpty()) {
            return PronunciationSummary.emptyNoAudio();
        }

        // ── 1. Session-level averages (driven by 4 average columns) ──
        BigDecimal totOverall = BigDecimal.ZERO, totAcc = BigDecimal.ZERO;
        BigDecimal totFlu = BigDecimal.ZERO, totComp = BigDecimal.ZERO;
        for (MessagePronunciation m : messages) {
            if (m.getOverallScore()      != null) totOverall = totOverall.add(m.getOverallScore());
            if (m.getAccuracyScore()     != null) totAcc     = totAcc.add(m.getAccuracyScore());
            if (m.getFluencyScore()      != null) totFlu     = totFlu.add(m.getFluencyScore());
            if (m.getCompletenessScore() != null) totComp    = totComp.add(m.getCompletenessScore());
        }
        BigDecimal n = BigDecimal.valueOf(messages.size());
        BigDecimal avgOverall = totOverall.divide(n, 2, RoundingMode.HALF_UP);

        // ── 2. Flatten word_details across all messages, group by surface ──
        Map<String, WordAggregate> wordAgg = new HashMap<>();
        Map<String, PhonemeAggregate> phonemeAgg = new HashMap<>();
        for (MessagePronunciation m : messages) {
            List<AssessmentResult.WordAssessment> words = parseWordDetails(m.getWordDetails());
            for (AssessmentResult.WordAssessment w : words) {
                if (w.getWord() == null || w.getWord().isBlank()) continue;
                wordAgg.computeIfAbsent(w.getWord(), k -> new WordAggregate(k))
                        .add(w.getAccuracyScore(), w.getErrorType());
                if (w.getPhonemes() != null) {
                    for (AssessmentResult.PhonemeAssessment p : w.getPhonemes()) {
                        if (p.getPhoneme() == null || p.getPhoneme().isBlank()) continue;
                        phonemeAgg.computeIfAbsent(p.getPhoneme(), k -> new PhonemeAggregate(k))
                                .add(p.getAccuracyScore());
                    }
                }
            }
        }

        // ── 3. Threshold escalation: select weak words ──
        List<MispronouncedWord> weakWords = selectWeakWords(wordAgg);
        // ── 4. Threshold escalation: select weak phonemes ──
        List<WeakPhonemeClass> weakPhonemes = selectWeakPhonemes(phonemeAgg);

        // ── 5. Determine output case (4-state) ──
        boolean cleanSession = weakWords.isEmpty()
                && weakPhonemes.isEmpty()
                && avgOverall.doubleValue() >= CLEAN_OVERALL;

        return PronunciationSummary.builder()
                .averageOverall(avgOverall)
                .averageAccuracy(totAcc.divide(n, 2, RoundingMode.HALF_UP))
                .averageFluency(totFlu.divide(n, 2, RoundingMode.HALF_UP))
                .averageCompleteness(totComp.divide(n, 2, RoundingMode.HALF_UP))
                .totalAssessed(messages.size())
                .weakWords(weakWords)
                .weakPhonemes(weakPhonemes)
                .cleanSession(cleanSession)
                .noAudio(false)
                .build();
    }

    private List<MispronouncedWord> selectWeakWords(Map<String, WordAggregate> agg) {
        List<MispronouncedWord> critical = collectWords(agg, WORD_CRITICAL);
        if (!critical.isEmpty()) return critical;
        return collectWords(agg, WORD_MARGINAL);
    }

    private List<MispronouncedWord> collectWords(Map<String, WordAggregate> agg, double cutoff) {
        List<MispronouncedWord> out = new ArrayList<>();
        for (WordAggregate w : agg.values()) {
            double avg = w.average();
            if (avg >= cutoff) continue;
            String dominantError = w.dominantErrorType();
            JapanesePhonemeTierMap.Tier tier =
                    JapanesePhonemeTierMap.tierForErrorType(dominantError);
            double impact = computeImpact(avg, w.count, tier.weight());
            out.add(new MispronouncedWord(
                    w.word,
                    BigDecimal.valueOf(avg).setScale(2, RoundingMode.HALF_UP),
                    w.count,
                    dominantError,
                    JapanesePhonemeTierMap.errorTypeLabel(dominantError),
                    BigDecimal.valueOf(impact).setScale(2, RoundingMode.HALF_UP)
            ));
        }
        out.sort(Comparator.comparing(MispronouncedWord::impact).reversed());
        return out;
    }

    private List<WeakPhonemeClass> selectWeakPhonemes(Map<String, PhonemeAggregate> agg) {
        List<WeakPhonemeClass> critical = collectPhonemes(agg, PHONEME_CRITICAL);
        if (!critical.isEmpty()) return critical;
        return collectPhonemes(agg, PHONEME_MARGINAL);
    }

    private List<WeakPhonemeClass> collectPhonemes(Map<String, PhonemeAggregate> agg, double cutoff) {
        List<WeakPhonemeClass> out = new ArrayList<>();
        for (PhonemeAggregate p : agg.values()) {
            double avg = p.average();
            if (avg >= cutoff) continue;
            JapanesePhonemeTierMap.TierEntry entry = JapanesePhonemeTierMap.lookup(p.ipa);
            double impact = computeImpact(avg, p.count, entry.tier().weight());
            out.add(new WeakPhonemeClass(
                    p.ipa,
                    entry.label(),
                    BigDecimal.valueOf(avg).setScale(2, RoundingMode.HALF_UP),
                    p.count,
                    entry.tier().name(),
                    entry.drill(),
                    BigDecimal.valueOf(impact).setScale(2, RoundingMode.HALF_UP)
            ));
        }
        out.sort(Comparator.comparing(WeakPhonemeClass::impact).reversed());
        return out;
    }

    /**
     * Impact = (100 − accuracy) × log(1 + occurrences) × tierWeight.
     * Used only for ordering (no top-N cap). Components rationale:
     * deficit linear in accuracy, occurrences with diminishing returns,
     * tier weight reflects Levis-style functional load.
     */
    private double computeImpact(double accuracy, int occurrences, double tierWeight) {
        double deficit = Math.max(0, 100 - accuracy);
        double occFactor = Math.log1p(occurrences);
        return deficit * occFactor * tierWeight;
    }

    private List<AssessmentResult.WordAssessment> parseWordDetails(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            JsonNode node = objectMapper.readTree(json);
            if (!node.isArray()) return List.of();
            return objectMapper.convertValue(node, WORD_LIST_TYPE);
        } catch (Exception e) {
            log.warn("Failed to parse word_details JSON: {}", e.getMessage());
            return List.of();
        }
    }

    // ── Private aggregation helpers ──────────────────────────────────────

    private static final class WordAggregate {
        final String word;
        double scoreSum = 0;
        int count = 0;
        Map<String, Integer> errorTypeCounts = new HashMap<>();

        WordAggregate(String word) { this.word = word; }

        void add(double score, String errorType) {
            scoreSum += score;
            count++;
            String type = errorType == null ? "None" : errorType;
            errorTypeCounts.merge(type, 1, Integer::sum);
        }

        double average() { return count == 0 ? 100 : scoreSum / count; }

        String dominantErrorType() {
            String best = "None";
            int bestCount = -1;
            for (var e : errorTypeCounts.entrySet()) {
                // Prefer non-"None" error types when tied — they're the actionable ones
                boolean prefer = e.getValue() > bestCount
                        || (e.getValue() == bestCount && !"None".equals(e.getKey()) && "None".equals(best));
                if (prefer) {
                    bestCount = e.getValue();
                    best = e.getKey();
                }
            }
            return best;
        }
    }

    private static final class PhonemeAggregate {
        final String ipa;
        double scoreSum = 0;
        int count = 0;

        PhonemeAggregate(String ipa) { this.ipa = ipa; }

        void add(double score) { scoreSum += score; count++; }
        double average() { return count == 0 ? 100 : scoreSum / count; }
    }

    // ── Public output records ────────────────────────────────────────────

    /** Session-level pronunciation summary; replaces the legacy averages-only DTO. */
    @lombok.Builder
    public record PronunciationSummary(
            BigDecimal averageOverall,
            BigDecimal averageAccuracy,
            BigDecimal averageFluency,
            BigDecimal averageCompleteness,
            int totalAssessed,
            List<MispronouncedWord> weakWords,
            List<WeakPhonemeClass> weakPhonemes,
            boolean cleanSession,
            boolean noAudio
    ) {
        public static PronunciationSummary emptyNoAudio() {
            return PronunciationSummary.builder()
                    .averageOverall(BigDecimal.ZERO)
                    .averageAccuracy(BigDecimal.ZERO)
                    .averageFluency(BigDecimal.ZERO)
                    .averageCompleteness(BigDecimal.ZERO)
                    .totalAssessed(0)
                    .weakWords(List.of())
                    .weakPhonemes(List.of())
                    .cleanSession(false)
                    .noAudio(true)
                    .build();
        }
    }

    public record MispronouncedWord(
            String word,
            BigDecimal averageAccuracy,
            int occurrences,
            String dominantErrorType,
            String errorTypeLabel,
            BigDecimal impact
    ) {}

    public record WeakPhonemeClass(
            String ipa,
            String label,
            BigDecimal averageScore,
            int occurrences,
            String tier,
            String drill,
            BigDecimal impact
    ) {}
}
