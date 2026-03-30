package com.fu.curriculumimport.service.embedding;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * RAG context retrieved from vector similarity search.
 * Provides existing knowledge to augment extraction prompts.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RagContext {

    /**
     * Similar content items found via embedding search.
     */
    private List<SimilarItem> similarItems;

    /**
     * Number of existing vocabulary in the same JLPT level.
     */
    private int existingVocabCount;

    /**
     * Number of existing grammar points in the same JLPT level.
     */
    private int existingGrammarCount;

    /**
     * Whether meaningful context was found.
     */
    public boolean hasContext() {
        return similarItems != null && !similarItems.isEmpty();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SimilarItem {
        private UUID contentId;
        private String contentType;
        private String textContent;
        private double similarity;
        /** Parsed metadata từ JSONB: lesson_id, course_id, jlpt_level, lesson_title... */
        @Builder.Default
        private Map<String, String> metadata = Map.of();
    }

    /**
     * Empty context when RAG is disabled or no results found.
     */
    public static RagContext empty() {
        return RagContext.builder()
                .similarItems(List.of())
                .existingVocabCount(0)
                .existingGrammarCount(0)
                .build();
    }
}
