package com.fu.curriculumimport.service.agent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Result from the ContentClassifierAgent.
 * Indicates what type of content a chunk contains.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClassificationResult {

    /**
     * Primary content type detected.
     */
    private ContentType contentType;

    /**
     * Confidence in the classification (0.0-1.0).
     */
    private double confidence;

    /**
     * Brief reasoning for the classification.
     */
    private String reasoning;

    /**
     * Estimated vocabulary items in this chunk.
     */
    private int estimatedVocabCount;

    /**
     * Estimated grammar points in this chunk.
     */
    private int estimatedGrammarCount;

    /**
     * Whether this chunk contains exercise/practice content.
     */
    private boolean containsExercises;

    /**
     * Content type categories
     */
    public enum ContentType {
        VOCABULARY,
        GRAMMAR,
        MIXED,
        CONVERSATION,
        EXERCISE,
        OTHER
    }

    /**
     * Create a default/fallback classification for error cases
     */
    public static ClassificationResult fallback() {
        return ClassificationResult.builder()
                .contentType(ContentType.MIXED)
                .confidence(0.3)
                .reasoning("Fallback classification due to processing error")
                .build();
    }
}
