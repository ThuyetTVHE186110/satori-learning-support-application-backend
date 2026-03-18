package com.fu.curriculumimport.service.embedding;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fu.curriculumimport.config.AgenticRagProperties;
import com.fu.curriculumimport.service.agent.ExtractionResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Embedding Service — Vector operations for the Agentic RAG pipeline.
 *
 * Responsibilities:
 * 1. Generate embeddings using OpenAI text-embedding-3-small (1536 dims)
 * 2. Store embeddings in progress.content_embeddings via pgvector
 * 3. Similarity search for RAG retrieval (find existing similar content)
 * 4. Deduplication assistance (check if content already exists)
 *
 * Uses JdbcTemplate for pgvector native SQL operations since JPA
 * does not natively support the vector column type.
 *
 * Reference: RAG retrieval augmented generation for contextual extraction
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class EmbeddingService {

    private final OpenAiEmbeddingModel embeddingModel;
    private final JdbcTemplate jdbcTemplate;
    private final AgenticRagProperties ragProperties;
    private final ObjectMapper objectMapper;

    /**
     * Retrieve RAG context by finding similar existing content.
     * Called before extraction to provide context to specialized agents.
     *
     * @param text      the chunk text to find similar content for
     * @param topK      number of similar items to return
     * @param threshold minimum cosine similarity threshold
     * @return RAG context with similar items
     */
    public RagContext retrieveContext(String text, int topK, double threshold) {
        try {
            float[] embedding = generateEmbedding(text);
            if (embedding == null) return RagContext.empty();

            String vectorStr = arrayToVectorString(embedding);

            List<RagContext.SimilarItem> items = jdbcTemplate.query(
                    """
                    SELECT content_id, content_type, text_content,
                           1 - (embedding <=> ?::vector) AS similarity
                    FROM progress.content_embeddings
                    WHERE 1 - (embedding <=> ?::vector) > ?
                    ORDER BY embedding <=> ?::vector
                    LIMIT ?
                    """,
                    (rs, rowNum) -> RagContext.SimilarItem.builder()
                            .contentId(UUID.fromString(rs.getString("content_id")))
                            .contentType(rs.getString("content_type"))
                            .textContent(rs.getString("text_content"))
                            .similarity(rs.getDouble("similarity"))
                            .build(),
                    vectorStr, vectorStr, threshold, vectorStr, topK
            );

            // Format context string for injection into prompts
            String formattedContext = formatContextForPrompt(items);

            return RagContext.builder()
                    .similarItems(items)
                    .formattedContext(formattedContext)
                    .build();

        } catch (Exception e) {
            log.warn("RAG context retrieval failed: {}", e.getMessage());
            return RagContext.empty();
        }
    }

    /**
     * Retrieve RAG context with default settings from properties.
     */
    public RagContext retrieveContext(String text) {
        return retrieveContext(text, ragProperties.getRagTopK(), ragProperties.getRagSimilarityThreshold());
    }

    /**
     * Store embeddings for extracted vocabulary items.
     *
     * @param vocabulary list of extracted vocabulary
     * @param importId   the import ID for tracking
     * @return number of embeddings created
     */
    public int storeVocabularyEmbeddings(List<ExtractionResult.ExtractedVocabulary> vocabulary,
                                          UUID importId) {
        int count = 0;
        for (ExtractionResult.ExtractedVocabulary vocab : vocabulary) {
            try {
                String text = buildVocabularyEmbeddingText(vocab);
                float[] embedding = generateEmbedding(text);
                if (embedding == null) continue;

                UUID contentId = UUID.randomUUID();
                storeEmbedding(contentId, "VOCABULARY", text, embedding, count, importId,
                        Map.of("japaneseText", vocab.getJapaneseText(),
                                "jlptLevel", vocab.getJlptLevel() != null ? vocab.getJlptLevel() : "N5",
                                "partOfSpeech", vocab.getPartOfSpeech() != null ? vocab.getPartOfSpeech() : ""));
                count++;
            } catch (Exception e) {
                log.warn("Failed to store embedding for vocab '{}': {}",
                        vocab.getJapaneseText(), e.getMessage());
            }
        }
        return count;
    }

    /**
     * Store embeddings for extracted grammar points.
     *
     * @param grammarPoints list of extracted grammar points
     * @param importId      the import ID for tracking
     * @return number of embeddings created
     */
    public int storeGrammarEmbeddings(List<ExtractionResult.ExtractedGrammar> grammarPoints,
                                       UUID importId) {
        int count = 0;
        for (ExtractionResult.ExtractedGrammar grammar : grammarPoints) {
            try {
                String text = buildGrammarEmbeddingText(grammar);
                float[] embedding = generateEmbedding(text);
                if (embedding == null) continue;

                UUID contentId = UUID.randomUUID();
                storeEmbedding(contentId, "GRAMMAR", text, embedding, count, importId,
                        Map.of("pattern", grammar.getPattern(),
                                "jlptLevel", grammar.getJlptLevel() != null ? grammar.getJlptLevel() : "N5",
                                "category", grammar.getCategory() != null ? grammar.getCategory() : "OTHER"));
                count++;
            } catch (Exception e) {
                log.warn("Failed to store embedding for grammar '{}': {}",
                        grammar.getPattern(), e.getMessage());
            }
        }
        return count;
    }

    /**
     * Upsert embedding for an existing entity (vocabulary or grammar point) by its entity ID.
     * Uses the entity UUID as content_id with chunk_index = 0.
     * Called when a vocabulary/grammar item is updated (UC 2.4.4).
     *
     * @param entityId    the entity's UUID (used as content_id)
     * @param contentType "VOCABULARY" or "GRAMMAR"
     * @param richText    pre-built rich text combining all relevant fields
     */
    public void upsertEntityEmbedding(UUID entityId, String contentType, String richText) {
        try {
            float[] embedding = generateEmbedding(richText);
            if (embedding == null) return;

            String vectorStr = arrayToVectorString(embedding);

            jdbcTemplate.update(
                    """
                    INSERT INTO progress.content_embeddings
                        (id, content_type, content_id, embedding, text_content, metadata, chunk_index, import_id, token_count)
                    VALUES (uuid_generate_v4(), ?, ?, ?::vector, ?, '{}'::jsonb, 0, NULL, ?)
                    ON CONFLICT (content_type, content_id, chunk_index) DO UPDATE
                    SET embedding = EXCLUDED.embedding,
                        text_content = EXCLUDED.text_content,
                        updated_at = CURRENT_TIMESTAMP
                    """,
                    contentType, entityId, vectorStr, richText, richText.length() / 4
            );
            log.debug("Upserted embedding for {} entity {}", contentType, entityId);
        } catch (Exception e) {
            log.warn("Failed to upsert embedding for {} {}: {}", contentType, entityId, e.getMessage());
        }
    }

    /**
     * Delete embeddings for an entity that has been soft-deleted (UC 2.4.5).
     *
     * @param entityId    the entity's UUID (content_id)
     * @param contentType "VOCABULARY" or "GRAMMAR"
     */
    public void deleteEntityEmbedding(UUID entityId, String contentType) {
        try {
            int deleted = jdbcTemplate.update(
                    "DELETE FROM progress.content_embeddings WHERE content_id = ? AND content_type = ?",
                    entityId, contentType
            );
            log.debug("Deleted {} embedding row(s) for {} entity {}", deleted, contentType, entityId);
        } catch (Exception e) {
            log.warn("Failed to delete embedding for {} {}: {}", contentType, entityId, e.getMessage());
        }
    }

    /**
     * Purge all embeddings created by a specific import.
     * Used during rollback to clean up RAG context from a reverted import.
     *
     * @param importId the import whose embeddings should be deleted
     * @return number of embeddings deleted
     */
    public int purgeEmbeddingsByImportId(UUID importId) {
        try {
            int deleted = jdbcTemplate.update(
                    "DELETE FROM progress.content_embeddings WHERE import_id = ?",
                    importId
            );
            log.info("Purged {} embeddings for rolled-back import {}", deleted, importId);
            return deleted;
        } catch (Exception e) {
            log.warn("Failed to purge embeddings for import {}: {}", importId, e.getMessage());
            return 0;
        }
    }

    /**
     * Store a single embedding in the content_embeddings table.
     */
    private void storeEmbedding(UUID contentId, String contentType, String textContent,
                                 float[] embedding, int chunkIndex, UUID importId,
                                 Map<String, String> metadata) {
        String vectorStr = arrayToVectorString(embedding);
        String metadataJson = mapToJson(metadata);

        jdbcTemplate.update(
                """
                INSERT INTO progress.content_embeddings 
                    (id, content_type, content_id, embedding, text_content, metadata, chunk_index, import_id, token_count)
                VALUES (uuid_generate_v4(), ?, ?, ?::vector, ?, ?::jsonb, ?, ?, ?)
                ON CONFLICT (content_type, content_id, chunk_index) DO UPDATE 
                SET embedding = EXCLUDED.embedding,
                    text_content = EXCLUDED.text_content,
                    metadata = EXCLUDED.metadata,
                    updated_at = CURRENT_TIMESTAMP
                """,
                contentType, contentId, vectorStr, textContent, metadataJson,
                chunkIndex, importId, textContent.length() / 4
        );
    }

    /**
     * Generate embedding using OpenAI text-embedding-3-small.
     */
    private float[] generateEmbedding(String text) {
        try {
            String truncated = truncateForEmbedding(text);
            return embeddingModel.embed(truncated);
        } catch (Exception e) {
            log.error("Embedding generation failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Truncate text to fit within OpenAI embedding token limits (8,192 tokens).
     *
     * Japanese/CJK characters tokenize at ~1 token per character, while ASCII/Latin
     * text is ~4 characters per token. A naive character limit of 30,000 works for
     * English but allows up to ~30,000 tokens for Japanese content, causing HTTP 400.
     *
     * This method accumulates an estimated token count character-by-character and
     * stops at 7,500 tokens (leaving a safety buffer under the 8,192 limit).
     */
    private String truncateForEmbedding(String text) {
        if (text == null || text.isEmpty()) return text;

        final int MAX_TOKENS = 7500;
        double estimatedTokens = 0;
        int i = 0;

        for (; i < text.length() && estimatedTokens < MAX_TOKENS; i++) {
            char c = text.charAt(i);
            // CJK Unified Ideographs, Hiragana, Katakana, CJK Compatibility Ideographs, Hangul
            if ((c >= 0x3000 && c <= 0x9FFF) || (c >= 0xF900 && c <= 0xFAFF) || (c >= 0xAC00 && c <= 0xD7AF)) {
                estimatedTokens += 1.0;
            } else {
                estimatedTokens += 0.25; // ASCII/Latin: ~4 chars per token
            }
        }

        if (i >= text.length()) return text;

        log.debug("Truncated embedding input from {} to {} chars (~{} estimated tokens)",
                text.length(), i, (int) estimatedTokens);
        return text.substring(0, i);
    }

    /**
     * Build rich text for vocabulary embedding (combines all fields).
     */
    private String buildVocabularyEmbeddingText(ExtractionResult.ExtractedVocabulary vocab) {
        StringBuilder sb = new StringBuilder();
        sb.append(vocab.getJapaneseText());
        if (vocab.getReading() != null) sb.append(" (").append(vocab.getReading()).append(")");
        if (vocab.getMeaningEn() != null) sb.append(" - ").append(vocab.getMeaningEn());
        if (vocab.getMeaningVi() != null) sb.append(" - ").append(vocab.getMeaningVi());
        if (vocab.getPartOfSpeech() != null) sb.append(" [").append(vocab.getPartOfSpeech()).append("]");
        return sb.toString();
    }

    /**
     * Build rich text for grammar embedding (combines all fields).
     */
    private String buildGrammarEmbeddingText(ExtractionResult.ExtractedGrammar grammar) {
        StringBuilder sb = new StringBuilder();
        sb.append(grammar.getPattern());
        if (grammar.getMeaningEn() != null) sb.append(" - ").append(grammar.getMeaningEn());
        if (grammar.getMeaningVi() != null) sb.append(" - ").append(grammar.getMeaningVi());
        if (grammar.getShortExplanation() != null) sb.append(". ").append(grammar.getShortExplanation());
        return sb.toString();
    }

    /**
     * Format similar items into a context string for prompt injection.
     */
    private String formatContextForPrompt(List<RagContext.SimilarItem> items) {
        if (items.isEmpty()) return "";

        StringBuilder sb = new StringBuilder("Existing related content in the system:\n");
        for (RagContext.SimilarItem item : items) {
            sb.append(String.format("- [%s] %s (%.0f%% similar)\n",
                    item.getContentType(), item.getTextContent(),
                    item.getSimilarity() * 100));
        }
        return sb.toString();
    }

    /**
     * Convert float array to pgvector string format: [0.1,0.2,0.3,...]
     */
    private String arrayToVectorString(float[] array) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < array.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(array[i]);
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * Convert map to JSON string using Jackson ObjectMapper for proper escaping.
     */
    private String mapToJson(Map<String, String> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            log.warn("Failed to serialize metadata to JSON: {}", e.getMessage());
            return "{}";
        }
    }
}
