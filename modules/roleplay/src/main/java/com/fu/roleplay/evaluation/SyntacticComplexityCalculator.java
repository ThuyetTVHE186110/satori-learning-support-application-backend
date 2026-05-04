package com.fu.roleplay.evaluation;

import com.fu.common.util.JapaneseTokenizer;
import com.fu.course.entity.Course.JlptLevel;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

/**
 * Syntactic complexity scoring using Iwashita (2006) W/U + C/U metrics with
 * asymmetric scoring against per-level expected values.
 *
 * <p><b>Metrics (Iwashita 2006):</b>
 * <ul>
 *   <li><b>W/U</b> = total content tokens / total AS-units. Mean utterance
 *       length — primary predictor of L2 Japanese oral proficiency.</li>
 *   <li><b>C/U</b> = total clauses / total AS-units. Subordination ratio —
 *       reflects clause-combining capability.</li>
 * </ul>
 *
 * <p><b>Scoring:</b> per-metric asymmetric scoring against EXPECTED_WU/CU[target].
 * If actual ≥ expected → 100 (above-target rewarded). If actual &lt; expected →
 * 100 × (1 − diff / expected). Final M1_syn = arithmetic mean of two scores.
 *
 * <p><b>Insufficient data:</b> requires ≥2 AS-units and ≥20 content tokens for
 * reliable measurement. Below that threshold, returns insufficient marker
 * (score should not be reported).
 *
 * <p><b>Anchor values are PROVISIONAL.</b> Approximated from Iwashita 2006
 * reported developmental trends in OPI samples (n=33). OPI proficiency mapped
 * to JLPT approximately. Calibrate post-deployment.
 *
 * <p><b>References:</b>
 * <ul>
 *   <li>Iwashita, N. (2006). Syntactic complexity measures and their relation
 *       to oral proficiency in Japanese as a foreign language.
 *       <i>Language Assessment Quarterly</i>, 3(2), 151–170.
 *       DOI: 10.1207/s15434311laq0302_4</li>
 *   <li>Foster, P., Tonkyn, A., &amp; Wigglesworth, G. (2000). Measuring spoken
 *       language: A unit for all reasons. <i>Applied Linguistics</i>, 21(3),
 *       354–375. (AS-unit definition)</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class SyntacticComplexityCalculator {

    /** Expected mean words per AS-unit per JLPT target (Iwashita 2006 trends). */
    private static final Map<JlptLevel, Double> EXPECTED_WU = Map.of(
            JlptLevel.N5, 4.0,
            JlptLevel.N4, 6.0,
            JlptLevel.N3, 9.0,
            JlptLevel.N2, 12.0,
            JlptLevel.N1, 15.0
    );

    /** Expected mean clauses per AS-unit per JLPT target. */
    private static final Map<JlptLevel, Double> EXPECTED_CU = Map.of(
            JlptLevel.N5, 1.1,
            JlptLevel.N4, 1.25,
            JlptLevel.N3, 1.4,
            JlptLevel.N2, 1.55,
            JlptLevel.N1, 1.7
    );

    /** Minimum AS-units required for reliable W/U + C/U measurement. */
    private static final int MIN_AS_UNITS_FOR_VALIDITY = 2;

    /** Minimum content tokens required for reliable measurement. */
    private static final int MIN_TOKENS_FOR_VALIDITY = 20;

    /**
     * Sanity floor on words-per-AS-unit. Real spoken Japanese — even at beginner
     * level — averages ≥ ~1.5 content tokens per AS-unit. A computed value below
     * this threshold means the AS-unit detector inflated the count, which happens
     * when Speech-to-Text engines insert {@code ？} after every short fragment.
     * In that case the score is meaningless (not a measure of learner ability) so
     * we mark the syntactic dimension insufficient instead of reporting a misleading
     * near-zero score that drags the composite down (e.g., L=100 + S=0.4 → composite=60).
     */
    private static final double SANITY_MIN_W_PER_U = 1.5;

    private final ClauseDetector clauseDetector;

    public SyntacticResult compute(java.util.List<String> userMessages, JlptLevel target) {
        if (userMessages == null || userMessages.isEmpty()) {
            return SyntacticResult.notEnoughData();
        }
        String concatenated = String.join("\n", userMessages);
        var detection = clauseDetector.detect(concatenated);
        int totalTokens = JapaneseTokenizer.countContentTokens(userMessages);

        if (detection.asUnits() < MIN_AS_UNITS_FOR_VALIDITY
                || totalTokens < MIN_TOKENS_FOR_VALIDITY) {
            return SyntacticResult.notEnoughData();
        }

        double wPerU = (double) totalTokens / detection.asUnits();
        double cPerU = (double) detection.totalClauses() / detection.asUnits();

        // Detect Speech-to-Text artifact: STT may insert sentence punctuation after every
        // utterance fragment, inflating asUnits and crashing W/U well below realistic floor.
        // Reporting a score under these conditions misleads the learner — flag insufficient.
        if (wPerU < SANITY_MIN_W_PER_U) {
            return SyntacticResult.notEnoughData();
        }

        double wuScore = asym(wPerU, EXPECTED_WU.getOrDefault(target, EXPECTED_WU.get(JlptLevel.N5)));
        double cuScore = asym(cPerU, EXPECTED_CU.getOrDefault(target, EXPECTED_CU.get(JlptLevel.N5)));
        double score = (wuScore + cuScore) / 2.0;

        return SyntacticResult.builder()
                .score(toBig(score))
                .W_per_U(toBig(wPerU))
                .C_per_U(toBig(cPerU))
                .totalAS_Units(detection.asUnits())
                .totalClauses(detection.totalClauses())
                .clauseTypeBreakdown(detection.byType())
                .build();
    }

    private double asym(double actual, double expected) {
        if (actual >= expected) return 100.0;
        double diff = expected - actual;
        return Math.max(0, 100 * (1 - diff / expected));
    }

    private static BigDecimal toBig(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Result of syntactic complexity computation. {@code insufficient=true}
     * when input is too short for reliable measurement.
     */
    @Builder
    public record SyntacticResult(
            BigDecimal score,
            BigDecimal W_per_U,
            BigDecimal C_per_U,
            int totalAS_Units,
            int totalClauses,
            Map<ClauseDetector.ClauseType, Integer> clauseTypeBreakdown,
            boolean insufficient
    ) {
        public static SyntacticResult notEnoughData() {
            return SyntacticResult.builder()
                    .insufficient(true)
                    .score(BigDecimal.ZERO)
                    .totalAS_Units(0)
                    .totalClauses(0)
                    .clauseTypeBreakdown(Map.of())
                    .build();
        }
    }
}
