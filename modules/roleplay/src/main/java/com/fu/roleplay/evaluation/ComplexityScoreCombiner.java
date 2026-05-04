package com.fu.roleplay.evaluation;

import com.fu.course.entity.Course.JlptLevel;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Combines lexical and syntactic complexity sub-scores into the headline
 * Complexity dimension score.
 *
 * <p><b>Weighting:</b> 0.6 × M1_lex + 0.4 × M1_syn.
 *
 * <p><b>Rationale for unequal weighting:</b>
 * <ul>
 *   <li><b>Lexical (0.6 — primary):</b> deterministic DB lookup, more reliable
 *       for short text (Koizumi 2012 supports simple type counts for &lt;200 tokens).</li>
 *   <li><b>Syntactic (0.4 — secondary):</b> regex+POS-based clause detection,
 *       ~90% precision; more sensitive to text quality and Kuromoji
 *       morphological accuracy.</li>
 * </ul>
 *
 * <p>Weights are an engineering decision under uncertainty (Bachman &amp; Palmer
 * 2010 AUA framework), not empirically calibrated. Calibratable post-deployment.
 *
 * <p><b>Insufficient data handling:</b>
 * <ul>
 *   <li>If both sub-results insufficient → composite insufficient (no score).</li>
 *   <li>If only one insufficient → contributes 0 to weighted sum (composite
 *       still reported but flagged via partialMeasurement).</li>
 * </ul>
 *
 * <p><b>References:</b>
 * <ul>
 *   <li>Norris, J. M., &amp; Ortega, L. (2009). Towards an organic approach
 *       to investigating CAF. <i>Applied Linguistics</i>, 30(4), 555–578.
 *       (CAF complexity sub-construct framework)</li>
 *   <li>Bachman, L. F., &amp; Palmer, A. S. (2010). <i>Language Assessment
 *       in Practice</i>. Oxford University Press. (AUA framework)</li>
 *   <li>Koizumi, R. (2012). Relationships between text length and lexical
 *       diversity measures. <i>Vocabulary Learning and Instruction</i>, 1(1),
 *       60–69. (short-text reliability for type-count measures)</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class ComplexityScoreCombiner {

    private static final double LEX_WEIGHT = 0.6;
    private static final double SYN_WEIGHT = 0.4;

    private final LexicalComplexityCalculator lexCalc;
    private final SyntacticComplexityCalculator synCalc;

    public ComplexityResult compute(List<String> userMessages, JlptLevel target) {
        var lex = lexCalc.compute(userMessages, target);
        var syn = synCalc.compute(userMessages, target);

        if (lex.insufficient() && syn.insufficient()) {
            return ComplexityResult.notEnoughData(lex, syn);
        }

        // Composite formula:
        // - Both available: 0.6 × lex + 0.4 × syn
        // - Only lex available (syn insufficient): use lex score alone (renormalized)
        // - Only syn available (lex insufficient): use syn score alone (renormalized)
        // Treating an insufficient dimension as a hard 0 (the previous behavior) penalizes
        // the learner for an UNMEASURABLE dimension, e.g. lex=100, syn=insufficient yielded
        // composite=60 — confusing because Vocabulary scored full marks. Renormalizing
        // makes "partial measurement" honest: report the available dimension as the score
        // and flag partialMeasurement so the FE can show "đang đo từ vựng / chưa đủ dữ liệu
        // câu" without appearing punitive.
        double composite;
        if (lex.insufficient()) {
            composite = syn.score().doubleValue();
        } else if (syn.insufficient()) {
            composite = lex.score().doubleValue();
        } else {
            composite = LEX_WEIGHT * lex.score().doubleValue()
                      + SYN_WEIGHT * syn.score().doubleValue();
        }

        return ComplexityResult.builder()
                .score(BigDecimal.valueOf(composite).setScale(2, RoundingMode.HALF_UP))
                .lexical(lex)
                .syntactic(syn)
                .partialMeasurement(lex.insufficient() || syn.insufficient())
                .insufficient(false)
                .build();
    }

    /**
     * Combined complexity result. {@code score} is the headline 0.6/0.4
     * composite. Sub-results retain full per-dimension diagnostics for UI
     * rendering and thesis-quality reporting.
     */
    @Builder
    public record ComplexityResult(
            BigDecimal score,
            LexicalComplexityCalculator.LexicalResult lexical,
            SyntacticComplexityCalculator.SyntacticResult syntactic,
            boolean insufficient,
            boolean partialMeasurement
    ) {
        public static ComplexityResult notEnoughData(
                LexicalComplexityCalculator.LexicalResult lex,
                SyntacticComplexityCalculator.SyntacticResult syn) {
            return ComplexityResult.builder()
                    .score(BigDecimal.ZERO)
                    .lexical(lex)
                    .syntactic(syn)
                    .insufficient(true)
                    .partialMeasurement(false)
                    .build();
        }
    }
}
