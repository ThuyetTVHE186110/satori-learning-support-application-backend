package com.fu.curriculumimport.service.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fu.curriculumimport.service.ingestion.DocumentChunk;
import com.fu.curriculumimport.util.LlmResponseUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * AI Agent responsible for extracting structured content from document chunks.
 * Uses OpenAI GPT-4o-mini with carefully engineered prompts for:
 * - Content classification (vocabulary / grammar / mixed / other)
 * - Vocabulary extraction with readings, meanings, POS
 * - Grammar pattern extraction with explanations
 * - Lesson structure generation
 *
 * Implements Agentic RAG pattern:
 * 1. Content Classifier → routes chunk to appropriate extractor
 * 2. Vocabulary Extractor → extracts vocab with JP/VN/EN translations
 * 3. Grammar Extractor → extracts grammar with structure + examples
 * 4. Lesson Structurer → organizes extracted content into lessons
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ContentExtractionAgent {

    private final OpenAiChatModel chatModel;
    private final ObjectMapper objectMapper;

    private static final String SYSTEM_PROMPT = """
            You are an expert Japanese language curriculum analyzer. Your task is to extract
            structured learning content from Japanese language textbook pages.
            
            You MUST respond ONLY with valid JSON. No explanations, no markdown.
            
            JLPT Levels: N5 (beginner) → N1 (advanced).
            """;

    private static final String EXTRACTION_PROMPT = """
            Analyze the following textbook content and extract ALL vocabulary and grammar points.
            
            Content from pages %d-%d:
            ---
            %s
            ---
            
            Return JSON with this EXACT structure:
            {
              "contentType": "VOCABULARY" | "GRAMMAR" | "MIXED" | "OTHER",
              "confidence": 0.0-1.0,
              "lessons": [
                {
                  "title": "Lesson title in English",
                  "titleJapanese": "Title in Japanese (if found)",
                  "description": "Brief description",
                  "lessonType": "VOCABULARY" | "GRAMMAR" | "KANJI" | "MIXED",
                  "objectives": ["objective1", "objective2"],
                  "pageStart": %d,
                  "pageEnd": %d,
                  "orderIndex": 0
                }
              ],
              "vocabulary": [
                {
                  "japaneseText": "日本語",
                  "reading": "にほんご",
                  "romaji": "nihongo",
                  "meaningVi": "Tiếng Nhật",
                  "meaningEn": "Japanese language",
                  "partOfSpeech": "noun",
                  "jlptLevel": "N5",
                  "kanjiWriting": "日本語",
                  "exampleSentences": [{"jp": "...", "vi": "...", "en": "..."}],
                  "isKeyWord": true
                }
              ],
              "grammarPoints": [
                {
                  "pattern": "～ます",
                  "patternReading": "～ます",
                  "meaningVi": "Thể lịch sự",
                  "meaningEn": "Polite form",
                  "shortExplanation": "Used to make verbs polite",
                  "detailedExplanation": "Detailed usage...",
                  "jlptLevel": "N5",
                  "category": "VERB_FORM",
                  "exampleSentences": [{"jp": "...", "vi": "...", "en": "..."}],
                  "commonMistakes": [{"mistake": "...", "correction": "...", "explanation": "..."}],
                  "isKeyPoint": true
                }
              ]
            }
            
            Rules:
            - Extract ALL vocabulary and grammar points found in the text
            - Always provide both Vietnamese (Vi) and English (En) meanings
            - Detect JLPT level from context or default to N5
            - For vocabulary: include reading (hiragana), romaji, part of speech
            - For grammar: include pattern, category, examples, common mistakes
            - Grammar categories: VERB_FORM, PARTICLE, ADJECTIVE, CONJUGATION, SENTENCE_PATTERN, HONORIFIC, CONDITIONAL, PASSIVE, CAUSATIVE, OTHER
            - Set confidence based on clarity of source content
            """;

    /**
     * Extract structured content from a document chunk using AI.
     *
     * @param chunk      the document chunk to process
     * @param jlptLevel  the target JLPT level for the course (hint)
     * @return extraction result with vocabulary, grammar, and lessons
     */
    public ExtractionResult extractContent(DocumentChunk chunk, String jlptLevel) {
        long startTime = System.currentTimeMillis();

        try {
            String userPrompt = String.format(EXTRACTION_PROMPT,
                    chunk.getPageStart(), chunk.getPageEnd(),
                    chunk.getContent(),
                    chunk.getPageStart(), chunk.getPageEnd());

            ChatClient chatClient = ChatClient.builder(chatModel).build();
            String response = chatClient.prompt()
                    .system(SYSTEM_PROMPT + "\nTarget JLPT Level: " + jlptLevel)
                    .user(userPrompt)
                    .call()
                    .content();

            long processingTime = System.currentTimeMillis() - startTime;

            // Parse the JSON response
            return parseExtractionResponse(response, chunk.getChunkIndex(), processingTime);

        } catch (Exception e) {
            log.error("AI extraction failed for chunk {}: {}", chunk.getChunkIndex(), e.getMessage());
            long processingTime = System.currentTimeMillis() - startTime;
            return ExtractionResult.builder()
                    .chunkIndex(chunk.getChunkIndex())
                    .confidence(0.0)
                    .tokensUsed(0)
                    .processingTimeMs(processingTime)
                    .lessons(Collections.emptyList())
                    .vocabulary(Collections.emptyList())
                    .grammarPoints(Collections.emptyList())
                    .build();
        }
    }

    /**
     * Parse the AI JSON response into an ExtractionResult.
     */
    private ExtractionResult parseExtractionResponse(String jsonResponse, int chunkIndex, long processingTimeMs) {
        try {
            String cleaned = LlmResponseUtils.cleanJson(jsonResponse);

            Map<String, Object> parsed = objectMapper.readValue(cleaned, new TypeReference<>() {});

            double confidence = parsed.containsKey("confidence")
                    ? ((Number) parsed.get("confidence")).doubleValue() : 0.5;

            List<ExtractionResult.ExtractedLesson> lessons = parseField(parsed, "lessons",
                    new TypeReference<List<ExtractionResult.ExtractedLesson>>() {});

            List<ExtractionResult.ExtractedVocabulary> vocabulary = parseField(parsed, "vocabulary",
                    new TypeReference<List<ExtractionResult.ExtractedVocabulary>>() {});

            List<ExtractionResult.ExtractedGrammar> grammarPoints = parseField(parsed, "grammarPoints",
                    new TypeReference<List<ExtractionResult.ExtractedGrammar>>() {});

            return ExtractionResult.builder()
                    .chunkIndex(chunkIndex)
                    .confidence(confidence)
                    .tokensUsed(LlmResponseUtils.estimateTokens(jsonResponse))
                    .processingTimeMs(processingTimeMs)
                    .lessons(lessons != null ? lessons : Collections.emptyList())
                    .vocabulary(vocabulary != null ? vocabulary : Collections.emptyList())
                    .grammarPoints(grammarPoints != null ? grammarPoints : Collections.emptyList())
                    .build();

        } catch (JsonProcessingException e) {
            log.error("Failed to parse AI response for chunk {}: {}", chunkIndex, e.getMessage());
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

    private <T> T parseField(Map<String, Object> parsed, String field, TypeReference<T> type) {
        try {
            if (!parsed.containsKey(field)) return null;
            String json = objectMapper.writeValueAsString(parsed.get(field));
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse field '{}': {}", field, e.getMessage());
            return null;
        }
    }
}
