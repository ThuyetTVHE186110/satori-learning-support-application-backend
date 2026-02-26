package com.fu.curriculumimport.service.agent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Result from the ContentValidationAgent (Corrective RAG).
 * Contains validation status, issues found, and optionally corrected data.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ValidationResult {

    /**
     * Overall validation status.
     */
    private ValidationStatus status;

    /**
     * Overall quality score (0.0-1.0).
     */
    private double qualityScore;

    /**
     * List of issues found during validation.
     */
    private List<ValidationIssue> issues;

    /**
     * Whether the extraction should be re-attempted.
     */
    private boolean needsReExtraction;

    /**
     * Specific feedback to include in re-extraction prompt.
     */
    private String reExtractionHint;

    /**
     * Number of vocabulary items validated as valid.
     */
    private int validVocabCount;

    /**
     * Number of grammar points validated as valid.
     */
    private int validGrammarCount;

    /**
     * Number of items flagged for review.
     */
    private int flaggedCount;

    // ==================== Self-Reflection & Grounding ====================

    /**
     * Self-Reflection + Grounding verification results.
     * Null when self-reflection/grounding is disabled.
     */
    private ReflectionResult reflectionResult;

    /**
     * Number of items flagged as potentially hallucinated.
     */
    private int hallucinatedItemCount;

    /**
     * Combined quality score (rule-based + reflection + grounding).
     * When reflection is disabled, equals qualityScore.
     */
    private double combinedQualityScore;

    public enum ValidationStatus {
        VALID,
        INVALID,
        NEEDS_REVIEW,
        PARTIAL
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ValidationIssue {
        private String field;
        private String itemIdentifier;
        private IssueSeverity severity;
        private String message;
        private String suggestion;

        public enum IssueSeverity {
            ERROR,
            WARNING,
            INFO
        }
    }

    /**
     * Create a passing result
     */
    public static ValidationResult passed(int vocabCount, int grammarCount) {
        return ValidationResult.builder()
                .status(ValidationStatus.VALID)
                .qualityScore(1.0)
                .issues(List.of())
                .needsReExtraction(false)
                .validVocabCount(vocabCount)
                .validGrammarCount(grammarCount)
                .flaggedCount(0)
                .build();
    }
}
