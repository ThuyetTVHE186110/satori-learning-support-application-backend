package com.fu.roleplay.evaluation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fu.roleplay.entity.ConversationMessage;
import com.fu.roleplay.enums.ErrorType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Aggregates correction data from conversation messages into structured error
 * breakdowns using the CAF error-free-clause-ratio metric (Skehan 1998).
 *
 * <p><b>Methodology (Phase 2 v28 rewrite):</b>
 * <ul>
 *   <li>Each user message is segmented into AS-units (terminal punctuation count,
 *       Foster, Tonkyn &amp; Wigglesworth 2000).</li>
 *   <li>LLM-tagged corrections carry a {@code severity} (CRITICAL / MAJOR / MINOR)
 *       and {@code comprehensibilityImpact} (LOCAL / GLOBAL).</li>
 *   <li>Only CRITICAL + MAJOR errors count toward the score (MINOR = typo,
 *       Vann, Meyer &amp; Lorenz 1984 error gravity).</li>
 *   <li>A LOCAL serious error pollutes one clause within its message.</li>
 *   <li>A GLOBAL serious error pollutes all clauses of its message
 *       (Burt &amp; Kiparsky 1974 global vs local errors).</li>
 *   <li>Score = clean-clause / total-clause × 100.</li>
 *   <li>Any CRITICAL anywhere in the session caps score at 70 (categorical
 *       "communication breakdown" gate).</li>
 * </ul>
 *
 * <p>Pure computation — no LLM calls, no DB access.
 *
 * <p><b>References:</b>
 * <ul>
 *   <li>Skehan, P. (1998). <i>A cognitive approach to language learning</i>.
 *       Oxford University Press.</li>
 *   <li>Norris, J. M., &amp; Ortega, L. (2009). Towards an organic approach to
 *       investigating CAF in instructed SLA. <i>Applied Linguistics</i>, 30(4),
 *       555–578.</li>
 *   <li>Vann, R. J., Meyer, D. E., &amp; Lorenz, F. O. (1984). Error gravity.
 *       <i>TESOL Quarterly</i>, 18(3), 427–440.</li>
 *   <li>Burt, M. K., &amp; Kiparsky, C. (1974). Global and local mistakes.
 *       In Schumann &amp; Stenson (Eds.), <i>New frontiers in second language
 *       learning</i> (pp. 71–79). Newbury House.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ErrorPatternAggregator {

    private final ObjectMapper objectMapper;
    private final JapaneseClauseCounter clauseCounter;

    /**
     * Upper bound on collected correction examples per session. Lifted to 50 in
     * Phase 1 v28 so learners see all errors (pedagogical feedback requirement).
     * Typical sessions have &lt; 20 corrections; 50 guards pathological cases.
     */
    private static final int CORRECTION_EXAMPLES_HARD_CAP = 50;

    /**
     * Score cap applied when any CRITICAL error occurs in the session.
     * Communication breakdown is treated as a categorical event that limits
     * the achievable score regardless of clean-clause ratio.
     */
    private static final int CRITICAL_GATE_CAP = 70;

    private static final String SEVERITY_CRITICAL = "CRITICAL";
    private static final String SEVERITY_MAJOR = "MAJOR";
    private static final String SEVERITY_MINOR = "MINOR";
    private static final String IMPACT_GLOBAL = "GLOBAL";

    /**
     * Punctuation / whitespace characters injected by Speech-to-Text (Azure, Google,
     * device STT) that are NOT learner choices. A "correction" whose surface diff is
     * only these characters is an STT artifact, not a real mistake — drop it before
     * it pollutes the error breakdown and the learner-facing feedback.
     */
    private static final java.util.regex.Pattern STT_PUNCTUATION_ONLY = java.util.regex.Pattern.compile(
            "^[\\s。、！？!?,.…・「」『』（）()]*$");

    /**
     * Aggregate corrections into a structured breakdown.
     *
     * @param messages  full session message history (USER + ASSISTANT)
     * @param jlptLevel target JLPT level (currently informational; reserved for
     *                  future per-level tuning)
     */
    public ErrorBreakdown aggregate(List<ConversationMessage> messages, String jlptLevel) {
        // Index ASSISTANT corrections by the USER message they refer to.
        // Convention: the corrections JSON on assistant message N applies to
        // the immediately preceding user message (index N-1 in chronological order).
        List<ConversationMessage> chronological = new ArrayList<>(messages);

        Map<ErrorType, Integer> errorCounts = new EnumMap<>(ErrorType.class);
        Map<String, Integer> severityCounts = new HashMap<>();
        severityCounts.put(SEVERITY_CRITICAL, 0);
        severityCounts.put(SEVERITY_MAJOR, 0);
        severityCounts.put(SEVERITY_MINOR, 0);

        List<CorrectionExample> topExamples = new ArrayList<>();
        int totalCorrections = 0;
        int totalClauses = 0;
        int totalDirty = 0;
        int userMessageCount = 0;

        for (int i = 0; i < chronological.size(); i++) {
            ConversationMessage msg = chronological.get(i);
            if (msg.getRole() != ConversationMessage.MessageRole.USER) continue;
            userMessageCount++;

            int clauseCount = clauseCounter.countAS_Units(msg.getContent());
            totalClauses += clauseCount;

            // Find the next ASSISTANT message that holds corrections for this user turn.
            ConversationMessage assistantReply = findNextAssistant(chronological, i);
            if (assistantReply == null) continue;

            List<Map<String, String>> corrections = parseCorrections(assistantReply);
            if (corrections.isEmpty()) continue;

            int seriousLocal = 0;
            boolean seriousGlobal = false;

            for (Map<String, String> correction : corrections) {
                // Drop STT-artifact corrections (LLM occasionally flags punctuation that the
                // STT engine inserted). These are NOT learner errors — counting them inflates
                // the correction count and shows confusing entries like `original: "?"`.
                if (isPunctuationOnlyCorrection(correction)) {
                    log.debug("Skipping STT-artifact correction: original='{}' corrected='{}'",
                            correction.get("original"), correction.get("corrected"));
                    continue;
                }
                totalCorrections++;
                ErrorType type = parseErrorType(correction.get("errorType"));
                errorCounts.merge(type, 1, Integer::sum);

                String severity = normalizeSeverity(correction.get("severity"));
                severityCounts.merge(severity, 1, Integer::sum);

                String impact = correction.getOrDefault("comprehensibilityImpact", "");
                boolean isGlobal = IMPACT_GLOBAL.equalsIgnoreCase(impact);

                if (SEVERITY_CRITICAL.equals(severity) || SEVERITY_MAJOR.equals(severity)) {
                    if (isGlobal) {
                        seriousGlobal = true;
                    } else {
                        seriousLocal++;
                    }
                }

                if (topExamples.size() < CORRECTION_EXAMPLES_HARD_CAP) {
                    topExamples.add(new CorrectionExample(
                            correction.getOrDefault("original", ""),
                            correction.getOrDefault("corrected", ""),
                            correction.getOrDefault("explanation", ""),
                            type,
                            severity,
                            isGlobal ? IMPACT_GLOBAL : (impact.isEmpty() ? "LOCAL" : impact.toUpperCase()),
                            correction.getOrDefault("severityReasoning", "")
                    ));
                }
            }

            int dirty = seriousGlobal ? clauseCount : Math.min(clauseCount, seriousLocal);
            totalDirty += dirty;
        }

        int totalClean = totalClauses - totalDirty;
        boolean hasCritical = severityCounts.getOrDefault(SEVERITY_CRITICAL, 0) > 0;

        BigDecimal accuracyScore = computeAccuracyScore(totalClean, totalClauses, hasCritical);
        BigDecimal errorRate = computeLegacyErrorRate(totalCorrections, userMessageCount);

        return new ErrorBreakdown(
                totalCorrections,
                userMessageCount,
                totalClauses,
                totalClean,
                totalDirty,
                errorRate,
                accuracyScore,
                hasCritical,
                errorCounts,
                severityCounts,
                topExamples
        );
    }

    /**
     * Locate the next ASSISTANT message after a USER turn — this is where
     * corrections (if any) live per the AI prompt contract.
     */
    private ConversationMessage findNextAssistant(List<ConversationMessage> messages, int userIndex) {
        for (int j = userIndex + 1; j < messages.size(); j++) {
            ConversationMessage candidate = messages.get(j);
            if (candidate.getRole() == ConversationMessage.MessageRole.ASSISTANT) {
                return candidate;
            }
            // Skip SYSTEM messages but stop at next USER (no assistant reply yet).
            if (candidate.getRole() == ConversationMessage.MessageRole.USER) {
                return null;
            }
        }
        return null;
    }

    private List<Map<String, String>> parseCorrections(ConversationMessage assistantReply) {
        if (assistantReply.getCorrections() == null
                || assistantReply.getCorrections().equals("[]")) {
            return List.of();
        }
        try {
            return objectMapper.readValue(assistantReply.getCorrections(), new TypeReference<>() {});
        } catch (Exception e) {
            log.debug("Failed to parse corrections JSON for message {}: {}",
                    assistantReply.getId(), e.getMessage());
            return List.of();
        }
    }

    private BigDecimal computeAccuracyScore(int clean, int total, boolean hasCritical) {
        if (total == 0) return BigDecimal.valueOf(100).setScale(2, RoundingMode.HALF_UP);
        double rawScore = clean * 100.0 / total;
        if (hasCritical) {
            rawScore = Math.min(CRITICAL_GATE_CAP, rawScore);
        }
        return BigDecimal.valueOf(Math.max(0, Math.min(100, rawScore)))
                .setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Legacy errors-per-message rate for backward compatibility with consumers
     * (cross-session tracker, FE legacy renderer). The accuracy score itself
     * uses clean-clause ratio (Skehan CAF), not this rate.
     */
    private BigDecimal computeLegacyErrorRate(int corrections, int userMessages) {
        if (userMessages == 0) return BigDecimal.ZERO;
        return BigDecimal.valueOf(corrections)
                .divide(BigDecimal.valueOf(userMessages), 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * @return {@code true} when the correction targets only Speech-to-Text-injected
     * punctuation/whitespace (and so is not a real learner mistake). Matches when
     * {@code original} is empty / pure punctuation, OR when {@code original} and
     * {@code corrected} differ ONLY in punctuation/whitespace once stripped.
     */
    private static boolean isPunctuationOnlyCorrection(Map<String, String> correction) {
        if (correction == null) return false;
        String original  = nullToEmpty(correction.get("original"));
        String corrected = nullToEmpty(correction.get("corrected"));
        // Pure-punctuation original is always an STT artifact (e.g., LLM flagged "?").
        if (STT_PUNCTUATION_ONLY.matcher(original).matches()) return true;
        // Original and corrected identical after stripping punctuation+whitespace
        // → only punctuation differs, also an STT artifact (engine inserted/dropped it).
        String stripPattern = "[\\s。、！？!?,.…・「」『』（）()]+";
        String strippedOrig = original.replaceAll(stripPattern, "");
        String strippedCorr = corrected.replaceAll(stripPattern, "");
        return !strippedOrig.isEmpty() && strippedOrig.equals(strippedCorr) && !original.equals(corrected);
    }

    private static String nullToEmpty(String s) { return s == null ? "" : s; }

    private ErrorType parseErrorType(String raw) {
        if (raw == null || raw.isBlank()) return ErrorType.OTHER;
        try {
            return ErrorType.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return ErrorType.OTHER;
        }
    }

    private String normalizeSeverity(String raw) {
        if (raw == null || raw.isBlank()) return SEVERITY_MAJOR;  // sensible default
        String upper = raw.trim().toUpperCase();
        if (SEVERITY_CRITICAL.equals(upper) || SEVERITY_MAJOR.equals(upper) || SEVERITY_MINOR.equals(upper)) {
            return upper;
        }
        return SEVERITY_MAJOR;
    }

    /**
     * Aggregated session error data. Field set extended in Phase 2 v28 with
     * clause-based metrics and severity counts. Existing accessors
     * ({@code accuracyScore}, {@code errorRate}, {@code totalCorrections},
     * {@code userMessageCount}, {@code errorCounts}, {@code topExamples})
     * remain stable for backward compatibility.
     */
    public record ErrorBreakdown(
            int totalCorrections,
            int userMessageCount,
            int totalClauses,
            int cleanClauses,
            int dirtyClauses,
            BigDecimal errorRate,
            BigDecimal accuracyScore,
            boolean hasCritical,
            Map<ErrorType, Integer> errorCounts,
            Map<String, Integer> severityCounts,
            List<CorrectionExample> topExamples
    ) {
        public Map<String, Integer> errorCountsAsStringMap() {
            return errorCounts.entrySet().stream()
                    .collect(Collectors.toMap(e -> e.getKey().name(), Map.Entry::getValue));
        }
    }

    /**
     * Single correction example. Phase 2 v28 added severity, comprehensibility
     * impact, and severity reasoning fields. Pedagogical fields (pedagogicalTag,
     * howToFix, studyTip) will be added in Phase 2.5.
     */
    public record CorrectionExample(
            String original,
            String corrected,
            String explanation,
            ErrorType errorType,
            String severity,                 // CRITICAL | MAJOR | MINOR
            String comprehensibilityImpact,  // LOCAL | GLOBAL
            String severityReasoning
    ) {}
}
