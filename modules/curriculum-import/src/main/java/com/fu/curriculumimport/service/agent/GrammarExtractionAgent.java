package com.fu.curriculumimport.service.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fu.curriculumimport.service.agent.prompt.GrammarPromptBuilder;
import com.fu.curriculumimport.service.embedding.RagContext;
import com.fu.curriculumimport.service.ingestion.DocumentChunk;
import com.fu.curriculumimport.service.ingestion.OcrTextPreprocessor;
import com.fu.curriculumimport.util.LlmResponseUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Specialized Grammar Extraction Agent.
 *
 * Designed for chunks classified as GRAMMAR content.
 * Uses a focused prompt optimized for extracting:
 * - Grammar pattern (e.g., ～ます, ～ている)
 * - Pattern reading in hiragana
 * - Vietnamese and English explanations
 * - Conjugation rules
 * - Category (VERB_FORM, PARTICLE, etc.)
 * - Example sentences with translations
 * - Common mistakes and corrections
 *
 * <p>Prompt strings live in {@link GrammarPromptBuilder} — this class handles
 * only LLM-call mechanics and response parsing.
 *
 * Reference: Agentic RAG specialized worker pattern
 */
@Service
@Slf4j
public class GrammarExtractionAgent {

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;

    public GrammarExtractionAgent(OpenAiChatModel chatModel, ObjectMapper objectMapper) {
        this.chatClient = ChatClient.builder(chatModel).build();
        this.objectMapper = objectMapper;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Extract grammar points from a chunk with optional RAG context.
     */
    public ExtractionResult extractGrammar(DocumentChunk chunk, String jlptLevel, RagContext ragContext) {
        long startTime = System.currentTimeMillis();

        if (OcrTextPreprocessor.isTrivial(chunk.getContent())) {
            log.info("Chunk {}: trivial content — skipping grammar LLM extraction",
                    chunk.getChunkIndex());
            return emptyResult(chunk.getChunkIndex(), System.currentTimeMillis() - startTime);
        }

        try {
            String ragSection = GrammarPromptBuilder.formatRagContext(ragContext);
            String userPrompt = GrammarPromptBuilder.buildExtractionPrompt(chunk, jlptLevel, ragSection, "");

            String response = chatClient.prompt()
                    .system(GrammarPromptBuilder.SYSTEM_PROMPT)
                    .user(userPrompt)
                    .options(OpenAiChatOptions.builder().maxTokens(4096).build())
                    .call()
                    .content();

            return parseResponse(response, chunk.getChunkIndex(), System.currentTimeMillis() - startTime);

        } catch (Exception e) {
            log.error("Grammar extraction failed for chunk {}: {}", chunk.getChunkIndex(), e.getMessage());
            return emptyResult(chunk.getChunkIndex(), System.currentTimeMillis() - startTime);
        }
    }

    /**
     * Re-extract with validation feedback (Corrective RAG).
     */
    public ExtractionResult reExtractWithFeedback(DocumentChunk chunk, String jlptLevel,
                                                    RagContext ragContext, String validationFeedback) {
        long startTime = System.currentTimeMillis();

        try {
            String ragSection = GrammarPromptBuilder.formatRagContext(ragContext);
            String feedbackSection = "\n\nPREVIOUS EXTRACTION ISSUES (please fix):\n" + validationFeedback;
            String userPrompt = GrammarPromptBuilder.buildExtractionPrompt(
                    chunk, jlptLevel, ragSection, feedbackSection);

            String response = chatClient.prompt()
                    .system(GrammarPromptBuilder.SYSTEM_PROMPT
                            + GrammarPromptBuilder.REEXTRACTION_SYSTEM_SUFFIX)
                    .user(userPrompt)
                    .options(OpenAiChatOptions.builder().maxTokens(4096).build())
                    .call()
                    .content();

            return parseResponse(response, chunk.getChunkIndex(), System.currentTimeMillis() - startTime);

        } catch (Exception e) {
            log.error("Grammar re-extraction failed for chunk {}: {}", chunk.getChunkIndex(), e.getMessage());
            return emptyResult(chunk.getChunkIndex(), System.currentTimeMillis() - startTime);
        }
    }

    // ── Response parsing ──────────────────────────────────────────────────────

    private ExtractionResult parseResponse(String jsonResponse, int chunkIndex, long processingTimeMs) {
        try {
            String cleaned = LlmResponseUtils.cleanJson(jsonResponse);
            Map<String, Object> parsed = objectMapper.readValue(cleaned, new TypeReference<>() {});

            double confidence = ((Number) parsed.getOrDefault("confidence", 0.5)).doubleValue();

            List<ExtractionResult.ExtractedGrammar> grammarPoints = parseField(parsed, "grammarPoints",
                    new TypeReference<>() {});

            return ExtractionResult.builder()
                    .chunkIndex(chunkIndex)
                    .confidence(confidence)
                    .tokensUsed(LlmResponseUtils.estimateTokens(jsonResponse))
                    .processingTimeMs(processingTimeMs)
                    .lessons(Collections.emptyList())
                    .vocabulary(Collections.emptyList())
                    .grammarPoints(grammarPoints != null ? grammarPoints : Collections.emptyList())
                    .build();

        } catch (JsonProcessingException e) {
            log.error("Failed to parse grammar extraction response for chunk {}: {}", chunkIndex, e.getMessage());
            return emptyResult(chunkIndex, processingTimeMs);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private <T> T parseField(Map<String, Object> parsed, String field, TypeReference<T> type) {
        try {
            if (!parsed.containsKey(field)) return null;
            String json = objectMapper.writeValueAsString(parsed.get(field));
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private ExtractionResult emptyResult(int chunkIndex, long processingTimeMs) {
        return ExtractionResult.builder()
                .chunkIndex(chunkIndex)
                .confidence(0.0)
                .tokensUsed(0)
                .processingTimeMs(processingTimeMs)
                .lessons(Collections.emptyList())
                .vocabulary(Collections.emptyList())
                .grammarPoints(Collections.emptyList())
                .build();
    }
}
