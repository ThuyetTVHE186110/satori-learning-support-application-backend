package com.fu.curriculumimport.service.agent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Comprehensive result from the Self-Reflection + Grounding Verification pipeline.
 *
 * Combines three layers of validation per Self-RAG (Asai et al., 2023)
 * and CRAG (Yan et al., 2024):
 *
 * 1. Self-Reflection: LLM critiques its own extraction output using reflection tokens
 *    - [IsRelevant]: Is the extracted item relevant to the source content?
 *    - [IsSupported]: Is the extraction factually supported by the source?
 *    - [IsUseful]:    Is the extraction useful for language learning?
 *
 * 2. Grounding Verification: Algorithmic cross-reference against source text
 *    - Checks if extracted Japanese text / patterns exist in source chunk
 *    - Detects hallucinated content not present in source material
 *    - Verifies readings match the Japanese text context
 *
 * 3. Combined Assessment: Merges rule-based, LLM, and grounding results
 *    into a single actionable verdict.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReflectionResult {

    // ======================== Self-Reflection (LLM) ========================

    /**
     * Overall self-reflection assessment from LLM.
     */
    private ReflectionVerdict reflectionVerdict;

    /**
     * Aggregated reflection score (0.0 - 1.0).
     * Average of all item-level reflection scores.
     */
    private double reflectionScore;

    /**
     * LLM's reasoning about the extraction quality.
     */
    private String reflectionReasoning;

    /**
     * Per-item reflection assessments.
     */
    private List<ItemReflection> itemReflections;

    /**
     * Whether the LLM recommends re-extraction.
     */
    private boolean llmRecommendsReExtraction;

    /**
     * Specific LLM suggestions for improving extraction.
     */
    private String llmReExtractionSuggestion;

    // ======================== Grounding Verification ========================

    /**
     * Overall grounding score (0.0 - 1.0).
     * Proportion of extracted items that are grounded in source.
     */
    private double groundingScore;

    /**
     * Number of items verified as grounded in source text.
     */
    private int groundedCount;

    /**
     * Number of items detected as potentially hallucinated.
     */
    private int hallucinatedCount;

    /**
     * Per-item grounding results.
     */
    private List<GroundingCheck> groundingChecks;

    /**
     * List of specifically identified hallucinated items.
     */
    private List<String> hallucinatedItems;

    // ======================== Combined Assessment ========================

    /**
     * Final combined quality score incorporating all 3 layers.
     * Formula: 0.3 * ruleBasedScore + 0.35 * reflectionScore + 0.35 * groundingScore
     */
    private double combinedScore;

    /**
     * Final verdict after combining all assessments.
     */
    private CombinedVerdict combinedVerdict;

    /**
     * Whether re-extraction is needed based on combined assessment.
     */
    private boolean needsReExtraction;

    /**
     * Combined feedback for re-extraction prompt.
     */
    private String combinedReExtractionHint;

    // ======================== Inner Types ========================

    /**
     * Self-Reflection verdict from LLM.
     */
    public enum ReflectionVerdict {
        /** All items are relevant, supported, and useful */
        APPROVED,
        /** Most items are good with minor concerns */
        MOSTLY_APPROVED,
        /** Significant quality issues detected */
        NEEDS_IMPROVEMENT,
        /** Extraction is largely incorrect or irrelevant */
        REJECTED
    }

    /**
     * Combined final verdict.
     */
    public enum CombinedVerdict {
        /** High quality — rule-based, LLM, and grounding all pass */
        EXCELLENT,
        /** Acceptable quality — minor issues only */
        GOOD,
        /** Marginal — some issues but usable with review */
        MARGINAL,
        /** Poor quality — re-extraction recommended */
        POOR,
        /** Critical — mostly hallucinated or irrelevant */
        CRITICAL
    }

    /**
     * Per-item reflection assessment from LLM (Self-RAG reflection tokens).
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ItemReflection {
        /** The extracted item identifier (japanese text or pattern) */
        private String itemIdentifier;

        /** Content type: VOCABULARY or GRAMMAR */
        private String contentType;

        /** [IsRelevant] — Is this item relevant to the source content? */
        private boolean isRelevant;

        /** [IsSupported] — Is this item factually supported by the source? */
        private boolean isSupported;

        /** [IsUseful] — Is this item useful for language learning? */
        private boolean isUseful;

        /** Per-item reflection score (0.0 - 1.0) */
        private double score;

        /** LLM's reasoning for this specific item */
        private String reasoning;
    }

    /**
     * Per-item grounding verification result.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GroundingCheck {
        /** The extracted item identifier */
        private String itemIdentifier;

        /** Content type: VOCABULARY or GRAMMAR */
        private String contentType;

        /** Whether the main text/pattern was found in source */
        private boolean textGrounded;

        /** Whether the reading/pronunciation appears consistent */
        private boolean readingGrounded;

        /** Confidence of grounding check (0.0 - 1.0) */
        private double groundingConfidence;

        /** Specific evidence found in source (substring match) */
        private String evidenceSnippet;

        /** Whether this item is considered hallucinated */
        private boolean hallucinated;

        /** Reason for hallucination flag */
        private String hallucinationReason;
    }

    /**
     * Create a fully-passed result (for skipping reflection/grounding).
     */
    public static ReflectionResult passed() {
        return ReflectionResult.builder()
                .reflectionVerdict(ReflectionVerdict.APPROVED)
                .reflectionScore(1.0)
                .reflectionReasoning("Self-reflection skipped (disabled)")
                .itemReflections(List.of())
                .llmRecommendsReExtraction(false)
                .groundingScore(1.0)
                .groundedCount(0)
                .hallucinatedCount(0)
                .groundingChecks(List.of())
                .hallucinatedItems(List.of())
                .combinedScore(1.0)
                .combinedVerdict(CombinedVerdict.EXCELLENT)
                .needsReExtraction(false)
                .build();
    }
}
