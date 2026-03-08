package com.fu.curriculumimport.service.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fu.curriculumimport.service.embedding.RagContext;
import com.fu.curriculumimport.service.ingestion.DocumentChunk;
import com.fu.curriculumimport.util.LlmResponseUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
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

    private static final String SYSTEM_PROMPT = """
            You are an expert Japanese grammar analyzer for language learning.
            Extract ALL grammar points from the given textbook content with detailed explanations.
            
            You MUST respond ONLY with valid JSON. No explanations, no markdown code fences.
            
            Quality requirements:
            - Each grammar pattern must have clear structure notation
            - Explanations must be in BOTH Vietnamese and English
            - Provide at least one example sentence per pattern
            - Identify the correct grammar category
            - Note common mistakes learners make
            - Flag whether each pattern is a key grammar point
            """;

    private static final String EXTRACTION_PROMPT = """
            Extract ALL grammar points from this Japanese textbook content (pages %d-%d).
            Target JLPT level: %s
            
            Content:
            ---
            %s
            ---
            %s
            
            Return JSON:
            {
              "grammarPoints": [
                {
                  "pattern": "～ている",
                  "patternReading": "～ている",
                  "meaningVi": "Đang làm gì / Trạng thái",
                  "meaningEn": "Ongoing action / Resulting state",
                  "shortExplanation": "Expresses continuous action or resulting state",
                  "detailedExplanation": "V-て + いる indicates either an ongoing action (progressive) or a resulting state. Context determines which meaning applies.",
                  "jlptLevel": "%s",
                  "category": "VERB_FORM",
                  "exampleSentences": [
                    {
                      "jp": "今、本を読んでいます。",
                      "vi": "Bây giờ tôi đang đọc sách.",
                      "en": "I am reading a book now."
                    }
                  ],
                  "commonMistakes": [
                    {
                      "mistake": "食べています (for habitual action)",
                      "correction": "食べます (simple present for habits)",
                      "explanation": "～ている is for ongoing actions, not habitual ones in formal speech"
                    }
                  ],
                  "isKeyPoint": true
                }
              ],
              "confidence": 0.0-1.0
            }
            
            Grammar categories: VERB_FORM, PARTICLE, ADJECTIVE, CONJUGATION, 
            SENTENCE_PATTERN, HONORIFIC, CONDITIONAL, PASSIVE, CAUSATIVE, OTHER
            """;

    private static final String RAG_CONTEXT_TEMPLATE = """
            
            EXISTING GRAMMAR POINTS (avoid duplicating these):
            %s
            """;

    /**
     * Extract grammar points from a chunk with optional RAG context.
     */
    public ExtractionResult extractGrammar(DocumentChunk chunk, String jlptLevel, RagContext ragContext) {
        long startTime = System.currentTimeMillis();

        try {
            String ragSection = formatRagContext(ragContext);
            String userPrompt = String.format(EXTRACTION_PROMPT,
                    chunk.getPageStart(), chunk.getPageEnd(),
                    jlptLevel, chunk.getContent(), ragSection, jlptLevel);

            String response = chatClient.prompt()
                    .system(SYSTEM_PROMPT)
                    .user(userPrompt)
                    .call()
                    .content();

            long processingTime = System.currentTimeMillis() - startTime;
            return parseResponse(response, chunk.getChunkIndex(), processingTime);

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
            String ragSection = formatRagContext(ragContext);
            String feedbackSection = "\n\nPREVIOUS EXTRACTION ISSUES (please fix):\n" + validationFeedback;

            String userPrompt = String.format(EXTRACTION_PROMPT,
                    chunk.getPageStart(), chunk.getPageEnd(),
                    jlptLevel, chunk.getContent(), ragSection + feedbackSection, jlptLevel);

            String response = chatClient.prompt()
                    .system(SYSTEM_PROMPT + "\nIMPORTANT: Fix the issues noted in the feedback.")
                    .user(userPrompt)
                    .call()
                    .content();

            long processingTime = System.currentTimeMillis() - startTime;
            return parseResponse(response, chunk.getChunkIndex(), processingTime);

        } catch (Exception e) {
            log.error("Grammar re-extraction failed for chunk {}: {}", chunk.getChunkIndex(), e.getMessage());
            return emptyResult(chunk.getChunkIndex(), System.currentTimeMillis() - startTime);
        }
    }

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

    private String formatRagContext(RagContext ragContext) {
        if (ragContext == null || !ragContext.hasContext()) return "";

        StringBuilder sb = new StringBuilder();
        for (RagContext.SimilarItem item : ragContext.getSimilarItems()) {
            if ("GRAMMAR".equals(item.getContentType())) {
                sb.append("- ").append(item.getTextContent()).append("\n");
            }
        }

        return sb.isEmpty() ? "" : String.format(RAG_CONTEXT_TEMPLATE, sb);
    }

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
