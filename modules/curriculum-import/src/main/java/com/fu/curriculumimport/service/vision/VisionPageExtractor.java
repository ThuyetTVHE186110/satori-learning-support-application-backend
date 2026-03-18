package com.fu.curriculumimport.service.vision;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fu.curriculumimport.service.agent.ExtractionResult;
import com.fu.curriculumimport.util.LlmResponseUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.Media;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeTypeUtils;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Vision-first extraction for scanned PDF pages (Tier 2).
 *
 * <p>Sends rendered JPEG page images directly to GPT-4o Vision instead of relying on OCR text.
 * This preserves the original table layout, furigana positions, and multi-column formatting
 * that OCR typically destroys.
 *
 * <h3>When this is used</h3>
 * <ul>
 *   <li>Chunk type is {@code SCANNED} (image-based PDF)</li>
 *   <li>Section type is {@code VOCABULARY}, {@code GRAMMAR_PATTERN}, or
 *       {@code GRAMMAR_EXPLANATION}</li>
 *   <li>The structured table fast-path (MnNTableParser) returned no items</li>
 * </ul>
 *
 * <p>If vision extraction returns confidence ≥ {@link #VISION_CONFIDENCE_THRESHOLD},
 * the result is used directly; otherwise the text-based LLM path is tried as fallback.
 */
@Service
@Slf4j
public class VisionPageExtractor {

    /** GPT-4o model name — required for vision (image) input. */
    private static final String VISION_MODEL = "gpt-4o";

    /**
     * Maximum number of page images sent per API call.
     * A4 at 300 DPI encodes to ~200–500 KB JPEG; 4 pages ≈ 1–2 MB.
     */
    private static final int MAX_IMAGES_PER_CALL = 4;

    /**
     * Minimum confidence returned by vision extraction to accept the result
     * without falling back to the text-based LLM path.
     */
    public static final double VISION_CONFIDENCE_THRESHOLD = 0.75;

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;

    public VisionPageExtractor(OpenAiChatModel chatModel, ObjectMapper objectMapper) {
        this.chatClient = ChatClient.builder(chatModel).build();
        this.objectMapper = objectMapper;
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Extract vocabulary items from page image(s) using GPT-4o Vision.
     *
     * @param pageImages  JPEG-encoded page images (populated by ScannedPdfIngestionService)
     * @param jlptLevel   target JLPT level (e.g., "N5")
     * @param lessonTopic lesson title / topic for context
     * @param chunkIndex  chunk index for logging
     * @return extraction result; confidence is 0.0 on failure
     */
    public ExtractionResult extractVocabulary(List<byte[]> pageImages, String jlptLevel,
                                               String lessonTopic, int chunkIndex) {
        long t0 = System.currentTimeMillis();
        log.info("Vision vocabulary extraction: chunk={}, images={}, jlpt={}, lesson={}",
                chunkIndex, pageImages.size(), jlptLevel, lessonTopic);
        try {
            String level = jlptLevel != null ? jlptLevel : "N5";
            String topic = lessonTopic != null ? lessonTopic : "unknown";
            String userText = String.format(VisionExtractionPrompts.VOCABULARY_USER_TEMPLATE,
                    topic, level, level);

            UserMessage userMessage = buildMultimodalMessage(userText, pageImages);

            String rawResponse = chatClient.prompt()
                    .system(VisionExtractionPrompts.VOCABULARY_SYSTEM_PROMPT)
                    .messages(userMessage)
                    .options(OpenAiChatOptions.builder()
                            .model(VISION_MODEL)
                            .maxTokens(16384)
                            .build())
                    .call()
                    .content();

            return parseVocabularyResponse(rawResponse, chunkIndex, System.currentTimeMillis() - t0);

        } catch (Exception e) {
            log.error("Vision vocabulary extraction failed for chunk {}: {}", chunkIndex, e.getMessage());
            return emptyResult(chunkIndex, System.currentTimeMillis() - t0);
        }
    }

    /**
     * Extract grammar points from page image(s) using GPT-4o Vision.
     *
     * @param pageImages  JPEG-encoded page images
     * @param jlptLevel   target JLPT level
     * @param lessonTopic lesson title / topic for context
     * @param chunkIndex  chunk index for logging
     * @return extraction result; confidence is 0.0 on failure
     */
    public ExtractionResult extractGrammar(List<byte[]> pageImages, String jlptLevel,
                                            String lessonTopic, int chunkIndex) {
        long t0 = System.currentTimeMillis();
        log.info("Vision grammar extraction: chunk={}, images={}, jlpt={}, lesson={}",
                chunkIndex, pageImages.size(), jlptLevel, lessonTopic);
        try {
            String level = jlptLevel != null ? jlptLevel : "N5";
            String topic = lessonTopic != null ? lessonTopic : "unknown";
            String userText = String.format(VisionExtractionPrompts.GRAMMAR_USER_TEMPLATE,
                    topic, level, level);

            UserMessage userMessage = buildMultimodalMessage(userText, pageImages);

            String rawResponse = chatClient.prompt()
                    .system(VisionExtractionPrompts.GRAMMAR_SYSTEM_PROMPT)
                    .messages(userMessage)
                    .options(OpenAiChatOptions.builder()
                            .model(VISION_MODEL)
                            .maxTokens(16384)
                            .build())
                    .call()
                    .content();

            return parseGrammarResponse(rawResponse, chunkIndex, System.currentTimeMillis() - t0);

        } catch (Exception e) {
            log.error("Vision grammar extraction failed for chunk {}: {}", chunkIndex, e.getMessage());
            return emptyResult(chunkIndex, System.currentTimeMillis() - t0);
        }
    }

    // ── Internal helpers ───────────────────────────────────────────────────────

    private UserMessage buildMultimodalMessage(String text, List<byte[]> images) {
        int limit = Math.min(images.size(), MAX_IMAGES_PER_CALL);
        List<Media> mediaList = images.subList(0, limit).stream()
                .map(bytes -> Media.builder()
                        .mimeType(MimeTypeUtils.IMAGE_JPEG)
                        .data(bytes)
                        .build())
                .toList();
        return UserMessage.builder()
                .text(text)
                .media(mediaList)
                .build();
    }

    private ExtractionResult parseVocabularyResponse(String json, int chunkIndex, long ms) {
        try {
            Map<String, Object> parsed = objectMapper.readValue(
                    LlmResponseUtils.cleanJson(json), new TypeReference<>() {});
            double confidence = ((Number) parsed.getOrDefault("confidence", 0.5)).doubleValue();

            List<ExtractionResult.ExtractedVocabulary> vocab = parseField(
                    parsed, "vocabulary", new TypeReference<>() {});
            if (vocab != null) {
                vocab.forEach(v -> {
                    if (v.getMeaningSource() == null || v.getMeaningSource().isBlank()) {
                        v.setMeaningSource("SOURCE");
                    }
                });
            }
            log.info("Vision vocab result: chunk={}, items={}, confidence={}",
                    chunkIndex, vocab != null ? vocab.size() : 0, confidence);

            return ExtractionResult.builder()
                    .chunkIndex(chunkIndex)
                    .confidence(confidence)
                    .tokensUsed(LlmResponseUtils.estimateTokens(json))
                    .processingTimeMs(ms)
                    .lessons(Collections.emptyList())
                    .vocabulary(vocab != null ? vocab : Collections.emptyList())
                    .grammarPoints(Collections.emptyList())
                    .build();

        } catch (Exception e) {
            log.error("Failed to parse vision vocabulary response for chunk {}: {}", chunkIndex, e.getMessage());
            return emptyResult(chunkIndex, ms);
        }
    }

    private ExtractionResult parseGrammarResponse(String json, int chunkIndex, long ms) {
        try {
            Map<String, Object> parsed = objectMapper.readValue(
                    LlmResponseUtils.cleanJson(json), new TypeReference<>() {});
            double confidence = ((Number) parsed.getOrDefault("confidence", 0.5)).doubleValue();

            List<ExtractionResult.ExtractedGrammar> grammar = parseField(
                    parsed, "grammarPoints", new TypeReference<>() {});
            log.info("Vision grammar result: chunk={}, items={}, confidence={}",
                    chunkIndex, grammar != null ? grammar.size() : 0, confidence);

            return ExtractionResult.builder()
                    .chunkIndex(chunkIndex)
                    .confidence(confidence)
                    .tokensUsed(LlmResponseUtils.estimateTokens(json))
                    .processingTimeMs(ms)
                    .lessons(Collections.emptyList())
                    .vocabulary(Collections.emptyList())
                    .grammarPoints(grammar != null ? grammar : Collections.emptyList())
                    .build();

        } catch (Exception e) {
            log.error("Failed to parse vision grammar response for chunk {}: {}", chunkIndex, e.getMessage());
            return emptyResult(chunkIndex, ms);
        }
    }

    private <T> T parseField(Map<String, Object> parsed, String field, TypeReference<T> type) {
        try {
            if (!parsed.containsKey(field)) return null;
            return objectMapper.readValue(objectMapper.writeValueAsString(parsed.get(field)), type);
        } catch (Exception e) {
            return null;
        }
    }

    private ExtractionResult emptyResult(int chunkIndex, long ms) {
        return ExtractionResult.builder()
                .chunkIndex(chunkIndex).confidence(0.0).tokensUsed(0).processingTimeMs(ms)
                .lessons(Collections.emptyList()).vocabulary(Collections.emptyList())
                .grammarPoints(Collections.emptyList()).build();
    }
}
