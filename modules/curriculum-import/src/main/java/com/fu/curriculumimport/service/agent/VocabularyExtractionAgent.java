package com.fu.curriculumimport.service.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fu.curriculumimport.service.embedding.RagContext;
import com.fu.curriculumimport.service.ingestion.DocumentChunk;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Specialized Vocabulary Extraction Agent.
 *
 * Designed for chunks classified as VOCABULARY content.
 * Uses a focused prompt optimized for extracting:
 * - Japanese text (kanji/kana)
 * - Readings (hiragana)
 * - Romaji transliteration
 * - Vietnamese and English meanings
 * - Part of speech
 * - JLPT level
 * - Example sentences (trilingual)
 *
 * Accepts optional RAG context to avoid duplicating already-extracted vocabulary
 * and to provide consistent formatting.
 *
 * Reference: Agentic RAG specialized worker pattern
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class VocabularyExtractionAgent {

    private final OpenAiChatModel chatModel;
    private final ObjectMapper objectMapper;

    private static final String SYSTEM_PROMPT = """
            You are an expert Japanese vocabulary extractor for language learning.
            Extract ALL vocabulary items from the given textbook content with precise readings and meanings.
            
            You MUST respond ONLY with valid JSON. No explanations, no markdown code fences.
            
            Quality requirements:
            - Every Japanese word MUST have a hiragana reading
            - Meanings must be provided in BOTH Vietnamese and English
            - Part of speech must be accurate (noun, verb, adjective, adverb, particle, etc.)
            - Example sentences should demonstrate natural usage
            - Flag whether each word is a key vocabulary item for the lesson
            """;

    private static final String EXTRACTION_PROMPT = """
            Extract ALL vocabulary from this Japanese textbook content (pages %d-%d).
            Target JLPT level: %s
            
            Content:
            ---
            %s
            ---
            %s
            
            Return JSON array:
            {
              "vocabulary": [
                {
                  "japaneseText": "日本語",
                  "reading": "にほんご",
                  "romaji": "nihongo",
                  "meaningVi": "Tiếng Nhật",
                  "meaningEn": "Japanese language",
                  "partOfSpeech": "noun",
                  "jlptLevel": "%s",
                  "kanjiWriting": "日本語",
                  "exampleSentences": [
                    {"jp": "日本語を勉強します。", "vi": "Tôi học tiếng Nhật.", "en": "I study Japanese."}
                  ],
                  "isKeyWord": true
                }
              ],
              "confidence": 0.0-1.0
            }
            
            Part of speech values: noun, verb, i-adjective, na-adjective, adverb, particle, 
            conjunction, interjection, counter, prefix, suffix, expression
            """;

    private static final String RAG_CONTEXT_TEMPLATE = """
            
            EXISTING VOCABULARY (avoid duplicating these):
            %s
            """;

    /**
     * Extract vocabulary from a chunk with optional RAG context.
     */
    public ExtractionResult extractVocabulary(DocumentChunk chunk, String jlptLevel, RagContext ragContext) {
        long startTime = System.currentTimeMillis();

        try {
            String ragSection = formatRagContext(ragContext);
            String userPrompt = String.format(EXTRACTION_PROMPT,
                    chunk.getPageStart(), chunk.getPageEnd(),
                    jlptLevel, chunk.getContent(), ragSection, jlptLevel);

            ChatClient chatClient = ChatClient.builder(chatModel).build();
            String response = chatClient.prompt()
                    .system(SYSTEM_PROMPT)
                    .user(userPrompt)
                    .call()
                    .content();

            long processingTime = System.currentTimeMillis() - startTime;
            return parseResponse(response, chunk.getChunkIndex(), processingTime);

        } catch (Exception e) {
            log.error("Vocabulary extraction failed for chunk {}: {}", chunk.getChunkIndex(), e.getMessage());
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

            ChatClient chatClient = ChatClient.builder(chatModel).build();
            String response = chatClient.prompt()
                    .system(SYSTEM_PROMPT + "\nIMPORTANT: Fix the issues noted in the feedback.")
                    .user(userPrompt)
                    .call()
                    .content();

            long processingTime = System.currentTimeMillis() - startTime;
            return parseResponse(response, chunk.getChunkIndex(), processingTime);

        } catch (Exception e) {
            log.error("Vocabulary re-extraction failed for chunk {}: {}", chunk.getChunkIndex(), e.getMessage());
            return emptyResult(chunk.getChunkIndex(), System.currentTimeMillis() - startTime);
        }
    }

    private ExtractionResult parseResponse(String jsonResponse, int chunkIndex, long processingTimeMs) {
        try {
            String cleaned = cleanJson(jsonResponse);
            Map<String, Object> parsed = objectMapper.readValue(cleaned, new TypeReference<>() {});

            double confidence = ((Number) parsed.getOrDefault("confidence", 0.5)).doubleValue();

            List<ExtractionResult.ExtractedVocabulary> vocabulary = parseField(parsed, "vocabulary",
                    new TypeReference<>() {});

            return ExtractionResult.builder()
                    .chunkIndex(chunkIndex)
                    .confidence(confidence)
                    .tokensUsed(estimateTokens(jsonResponse))
                    .processingTimeMs(processingTimeMs)
                    .lessons(Collections.emptyList())
                    .vocabulary(vocabulary != null ? vocabulary : Collections.emptyList())
                    .grammarPoints(Collections.emptyList())
                    .build();

        } catch (JsonProcessingException e) {
            log.error("Failed to parse vocab extraction response for chunk {}: {}", chunkIndex, e.getMessage());
            return emptyResult(chunkIndex, processingTimeMs);
        }
    }

    private String formatRagContext(RagContext ragContext) {
        if (ragContext == null || !ragContext.hasContext()) return "";

        StringBuilder sb = new StringBuilder();
        for (RagContext.SimilarItem item : ragContext.getSimilarItems()) {
            if ("VOCABULARY".equals(item.getContentType())) {
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

    private String cleanJson(String json) {
        String cleaned = json.strip();
        if (cleaned.startsWith("```json")) cleaned = cleaned.substring(7);
        if (cleaned.startsWith("```")) cleaned = cleaned.substring(3);
        if (cleaned.endsWith("```")) cleaned = cleaned.substring(0, cleaned.length() - 3);
        return cleaned.strip();
    }

    private int estimateTokens(String text) {
        return text.length() / 4;
    }
}
