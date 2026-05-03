package com.fu.roleplay.evaluation;

import com.fu.common.util.JapaneseTokenizer;
import com.fu.course.entity.Course.JlptLevel;
import com.fu.course.entity.Vocabulary;
import com.fu.course.repository.VocabularyRepository;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Lexical complexity scoring via power mean (k=2) over learner vocabulary
 * level distribution, with asymmetric scoring against an expected mean.
 *
 * <p><b>Methodology:</b>
 * <ol>
 *   <li>Tokenize learner messages via Kuromoji, extract unique content-word
 *       base forms (lemmatized: 食べた → 食べる).</li>
 *   <li>Look up JLPT level per word in curriculum DB
 *       ({@link VocabularyRepository}). Words not in curriculum are tagged
 *       UNKNOWN and excluded from L_avg computation.</li>
 *   <li>Map each known word to a numeric level value: N5=1, N4=2, N3=3, N2=4, N1=5.</li>
 *   <li>Compute power mean k=2:
 *       <pre>L_avg = (Σ(level_i^2) / n)^(1/2)</pre>
 *       Power mean k=2 gives more weight to higher values than arithmetic mean —
 *       reflects that "harder words used at all" is informative even if rare.</li>
 *   <li>Compare L_avg against EXPECTED_L_AVG[target]:
 *       <ul>
 *         <li>L_avg ≥ expected → score = 100 (asymmetric: above-target rewarded)</li>
 *         <li>L_avg &lt; expected → score = 100 × (1 − diff / MAX_DIFF)</li>
 *       </ul>
 *   </li>
 * </ol>
 *
 * <p><b>Anchor values are PROVISIONAL.</b> EXPECTED_L_AVG reflects Zipfian-natural
 * production at each proficiency level (basic vocab dominates even for advanced
 * learners). Calibrate empirically post-deployment via Phase 3.6 SQL spike.
 *
 * <p><b>References:</b>
 * <ul>
 *   <li>Laufer, B., &amp; Nation, P. (1995). Vocabulary size and use: Lexical
 *       richness in L2 written production. <i>Applied Linguistics</i>, 16(3),
 *       307–322.</li>
 *   <li>Laufer, B. (1995). Beyond 2000: A measure of productive lexicon in a
 *       second language. In Eubank, Selinker &amp; Sharwood Smith (Eds.),
 *       <i>The current state of interlanguage</i> (pp. 265–272). Benjamins.</li>
 *   <li>Hardy, G. H., Littlewood, J. E., &amp; Pólya, G. (1934).
 *       <i>Inequalities</i> (power mean inequality, k≥1 amplifies higher values).</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LexicalComplexityCalculator {

    /** JLPT level → numeric value (lowest = N5 = 1, highest = N1 = 5). */
    private static final Map<JlptLevel, Integer> LEVEL_VALUE = Map.of(
            JlptLevel.N5, 1,
            JlptLevel.N4, 2,
            JlptLevel.N3, 3,
            JlptLevel.N2, 4,
            JlptLevel.N1, 5
    );

    /**
     * Expected L_avg per user JLPT target (provisional anchors).
     *
     * <p>Reflects Zipfian-natural distribution: basic vocab dominates even
     * advanced learners' production, so L_avg stays well below target_value.
     * E.g., N3 target (level=3) expects L_avg ≈ 1.8 because natural production
     * is mostly N5-N4 with sprinkled N3.
     */
    private static final Map<JlptLevel, Double> EXPECTED_L_AVG = Map.of(
            JlptLevel.N5, 1.2,
            JlptLevel.N4, 1.4,
            JlptLevel.N3, 1.8,
            JlptLevel.N2, 2.2,
            JlptLevel.N1, 2.6
    );

    /** Maximum diff considered "completely off" (L_avg = expected − 1.4 → score = 0). */
    private static final double MAX_DIFF = 1.4;

    /** Power mean exponent. k=2 amplifies higher level values vs arithmetic mean (k=1). */
    private static final int POWER_MEAN_K = 2;

    private final VocabularyRepository vocabRepo;

    public LexicalResult compute(List<String> userMessages, JlptLevel target) {
        Set<String> uniqueBaseForms = JapaneseTokenizer.extractContentBaseForms(userMessages);
        if (uniqueBaseForms.isEmpty()) return LexicalResult.notEnoughData();

        Map<JlptLevel, Integer> distribution = new EnumMap<>(JlptLevel.class);
        int knownCount = 0;
        int unknownCount = 0;
        double sumOfPowers = 0.0;

        for (String word : uniqueBaseForms) {
            JlptLevel level = lookupLevel(word);
            if (level == null) {
                unknownCount++;
                continue;
            }
            int value = LEVEL_VALUE.get(level);
            distribution.merge(level, 1, Integer::sum);
            sumOfPowers += Math.pow(value, POWER_MEAN_K);
            knownCount++;
        }

        if (knownCount == 0) return LexicalResult.notEnoughData();

        // Power mean k=2: L_avg = (Σ Level^2 / n)^(1/2)
        double L_avg = Math.pow(sumOfPowers / knownCount, 1.0 / POWER_MEAN_K);

        double expected = EXPECTED_L_AVG.getOrDefault(target, EXPECTED_L_AVG.get(JlptLevel.N5));
        double score;
        if (L_avg >= expected) {
            score = 100.0;
        } else {
            double diff = expected - L_avg;
            score = Math.max(0, 100 * (1 - diff / MAX_DIFF));
        }

        return LexicalResult.builder()
                .score(toBig(score))
                .L_avg(toBig(L_avg))
                .expectedL_avg(toBig(expected))
                .uniqueWords(uniqueBaseForms.size())
                .knownWords(knownCount)
                .unknownWords(unknownCount)
                .jlptDistribution(distribution)
                .build();
    }

    private JlptLevel lookupLevel(String word) {
        try {
            return vocabRepo
                    .findFirstByJapaneseTextAndDeletedAtIsNullOrderByCreatedAtAsc(word)
                    .map(Vocabulary::getJlptLevel)
                    .orElse(null);
        } catch (Exception e) {
            log.debug("Vocabulary lookup failed for '{}': {}", word, e.getMessage());
            return null;
        }
    }

    private static BigDecimal toBig(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Result of lexical complexity computation. {@code insufficient=true} when
     * no content tokens or no DB-known vocab found — score should not be reported.
     */
    @Builder
    public record LexicalResult(
            BigDecimal score,
            BigDecimal L_avg,
            BigDecimal expectedL_avg,
            int uniqueWords,
            int knownWords,
            int unknownWords,
            Map<JlptLevel, Integer> jlptDistribution,
            boolean insufficient
    ) {
        public static LexicalResult notEnoughData() {
            return LexicalResult.builder()
                    .insufficient(true)
                    .score(BigDecimal.ZERO)
                    .uniqueWords(0)
                    .knownWords(0)
                    .unknownWords(0)
                    .jlptDistribution(Map.of())
                    .build();
        }
    }
}
