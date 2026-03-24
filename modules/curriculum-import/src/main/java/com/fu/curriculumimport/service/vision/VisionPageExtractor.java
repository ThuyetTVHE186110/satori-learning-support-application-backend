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
 * <h3>Provider priority</h3>
 * <ol>
 *   <li><b>Gemini 2.5 Flash</b> — used when {@code curriculum-import.ocr.gemini-api-key} is set.
 *       Sends JPEG images to {@link GeminiVisionClient} with JSON schema enforcement,
 *       which guarantees structurally valid JSON output and costs ~95 % less than GPT-4o.</li>
 *   <li><b>GPT-4o Vision</b> — fallback when Gemini is not configured.
 *       Uses {@link OpenAiChatModel} via Spring AI {@link ChatClient}.</li>
 * </ol>
 *
 * <h3>When this is used</h3>
 * <ul>
 *   <li>Chunk type is {@code SCANNED} (image-based PDF)</li>
 *   <li>Section type is {@code VOCABULARY}, {@code GRAMMAR_PATTERN}, or
 *       {@code GRAMMAR_EXPLANATION}</li>
 *   <li>The structured table fast-path ({@link com.fu.curriculumimport.service.ingestion.MnNTableParser})
 *       returned no items</li>
 * </ul>
 *
 * <p>If vision extraction returns confidence ≥ {@link #VISION_CONFIDENCE_THRESHOLD},
 * the result is used directly; otherwise the text-based LLM path is tried as fallback.
 */
@Service
@Slf4j
public class VisionPageExtractor {

    /** GPT-4o model name — used only when Gemini is not configured. */
    private static final String GPT4O_MODEL = "gpt-4o";

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

    private final GeminiVisionClient geminiClient;
    private final ChatClient gpt4oChatClient;       // null when Gemini is primary
    private final ObjectMapper objectMapper;

    public VisionPageExtractor(GeminiVisionClient geminiClient,
                                OpenAiChatModel gpt4oChatModel,
                                ObjectMapper objectMapper) {
        this.geminiClient    = geminiClient;
        this.gpt4oChatClient = ChatClient.builder(gpt4oChatModel).build();
        this.objectMapper    = objectMapper;

        if (geminiClient.isAvailable()) {
            log.info("VisionPageExtractor: primary provider = Gemini {} (GPT-4o on standby)",
                    GeminiVisionClient.GEMINI_MODEL);
        } else {
            log.info("VisionPageExtractor: primary provider = GPT-4o (Gemini not configured)");
        }
    }

    // ── Text reconstruction prompts ─────────────────────────────────────────────

    private static final String TEXT_RECONSTRUCTION_SYSTEM =
            "You are a precise OCR engine for Japanese language textbook pages. "
            + "Output the page text exactly as written, preserving all scripts and formatting. "
            + "Do NOT add any commentary, markdown, or extra formatting.";

    private static final String TEXT_RECONSTRUCTION_PROMPT = """
            Tái tạo chính xác toàn bộ văn bản hiển thị trên các trang sách này.
            Đây là sách "Minna no Nihongo" (bản dịch và giải thích ngữ pháp tiếng Việt).

            QUY TẮC:
            - Giữ nguyên 100% ký tự tiếng Nhật (hiragana ひらがな, katakana カタカナ, kanji 漢字)
            - Giữ nguyên 100% văn bản tiếng Việt (bao gồm dấu thanh)
            - Nội dung trong ngoặc vuông [] LUÔN chứa ký tự tiếng Nhật — ví dụ: [は], [です], [か], [も], [の], [さん]
            - Giữ đúng số thứ tự: 1., 2., 1), 2), ①, ②, v.v.
            - Giữ đúng cấu trúc trang: tiêu đề mục, phần giải thích, câu ví dụ, [Chú ý]
            - Furigana nhỏ phía trên kanji → ghi dạng 漢字[ふりがな]
            - Mỗi trang cách nhau bằng một dòng trống
            - CHỈ xuất văn bản thuần — KHÔNG thêm markdown, tiêu đề bổ sung, hay bình luận
            """;

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Reconstruct clean text from page images, fixing corrupted Japanese characters
     * from Google Vision / Document AI OCR.
     *
     * <p>Provider priority: Gemini 2.5 Flash → GPT-4o Vision.
     * Uses whichever provider is available and succeeds first.
     *
     * @param pageImages JPEG-encoded page images, in page order
     * @return reconstructed plain text; empty string on total failure
     */
    public String reconstructText(List<byte[]> pageImages) {
        if (pageImages == null || pageImages.isEmpty()) return "";

        // ── Gemini path (preferred — cheaper) ────────────────────────────
        if (geminiClient.isAvailable()) {
            try {
                String result = geminiClient.reconstructText(pageImages);
                if (!result.isBlank()) {
                    log.debug("Text reconstruction via Gemini: {} chars from {} images",
                            result.length(), pageImages.size());
                    return result;
                }
            } catch (Exception e) {
                log.warn("Gemini text reconstruction failed: {} — trying GPT-4o", e.getMessage());
            }
        }

        // ── GPT-4o path (fallback) ───────────────────────────────────────
        log.debug("Text reconstruction via GPT-4o: {} images", pageImages.size());
        StringBuilder result = new StringBuilder();
        for (int start = 0; start < pageImages.size(); start += MAX_IMAGES_PER_CALL) {
            int end = Math.min(start + MAX_IMAGES_PER_CALL, pageImages.size());
            List<byte[]> batch = pageImages.subList(start, end);
            try {
                UserMessage msg = buildGpt4oMessage(TEXT_RECONSTRUCTION_PROMPT, batch);
                String text = gpt4oChatClient.prompt()
                        .system(TEXT_RECONSTRUCTION_SYSTEM)
                        .messages(msg)
                        .options(OpenAiChatOptions.builder()
                                .model(GPT4O_MODEL)
                                .maxTokens(16384)
                                .build())
                        .call()
                        .content();
                if (text != null && !text.isBlank()) {
                    if (!result.isEmpty()) result.append("\n\n");
                    result.append(text.strip());
                }
            } catch (Exception e) {
                log.warn("GPT-4o text reconstruction failed (batch {}-{}): {}",
                        start + 1, end, e.getMessage());
            }
        }
        return result.toString();
    }

    /**
     * Extract vocabulary items from page image(s).
     *
     * <p>Tries Gemini 2.5 Flash first; falls back to GPT-4o Vision when Gemini is
     * unavailable or returns an empty response.
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
        log.info("Vision vocabulary extraction: chunk={}, images={}, jlpt={}, lesson={}, provider={}",
                chunkIndex, pageImages.size(), jlptLevel, lessonTopic,
                geminiClient.isAvailable() ? "gemini" : "gpt-4o");

        // ── Gemini path (preferred) ────────────────────────────────────────
        if (geminiClient.isAvailable()) {
            String json = geminiClient.extractVocabularyJson(pageImages, lessonTopic, jlptLevel);
            if (!json.isBlank()) {
                return parseVocabularyResponse(json, chunkIndex, System.currentTimeMillis() - t0);
            }
            log.warn("Gemini vocab extraction returned empty for chunk {} — falling back to GPT-4o", chunkIndex);
        }

        // ── GPT-4o path (fallback) ─────────────────────────────────────────
        try {
            String level = jlptLevel   != null ? jlptLevel   : "N5";
            String topic = lessonTopic != null ? lessonTopic : "unknown";
            String userText = String.format(VisionExtractionPrompts.VOCABULARY_USER_TEMPLATE,
                    topic, level, level);

            UserMessage userMessage = buildGpt4oMessage(userText, pageImages);
            String rawResponse = gpt4oChatClient.prompt()
                    .system(VisionExtractionPrompts.VOCABULARY_SYSTEM_PROMPT)
                    .messages(userMessage)
                    .options(OpenAiChatOptions.builder()
                            .model(GPT4O_MODEL)
                            .maxTokens(16384)
                            .build())
                    .call()
                    .content();

            return parseVocabularyResponse(rawResponse, chunkIndex, System.currentTimeMillis() - t0);

        } catch (Exception e) {
            log.error("GPT-4o vocabulary extraction failed for chunk {}: {}", chunkIndex, e.getMessage());
            return emptyResult(chunkIndex, System.currentTimeMillis() - t0);
        }
    }

    /**
     * Extract grammar points from page image(s).
     *
     * <p>Tries Gemini 2.5 Flash first; falls back to GPT-4o Vision when Gemini is
     * unavailable or returns an empty response.
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
        log.info("Vision grammar extraction: chunk={}, images={}, jlpt={}, lesson={}, provider={}",
                chunkIndex, pageImages.size(), jlptLevel, lessonTopic,
                geminiClient.isAvailable() ? "gemini" : "gpt-4o");

        // ── Gemini path (preferred) ────────────────────────────────────────
        if (geminiClient.isAvailable()) {
            String json = geminiClient.extractGrammarJson(pageImages, lessonTopic, jlptLevel);
            if (!json.isBlank()) {
                return parseGrammarResponse(json, chunkIndex, System.currentTimeMillis() - t0);
            }
            log.warn("Gemini grammar extraction returned empty for chunk {} — falling back to GPT-4o", chunkIndex);
        }

        // ── GPT-4o path (fallback) ─────────────────────────────────────────
        try {
            String level = jlptLevel   != null ? jlptLevel   : "N5";
            String topic = lessonTopic != null ? lessonTopic : "unknown";
            String userText = String.format(VisionExtractionPrompts.GRAMMAR_USER_TEMPLATE,
                    topic, level, level);

            UserMessage userMessage = buildGpt4oMessage(userText, pageImages);
            String rawResponse = gpt4oChatClient.prompt()
                    .system(VisionExtractionPrompts.GRAMMAR_SYSTEM_PROMPT)
                    .messages(userMessage)
                    .options(OpenAiChatOptions.builder()
                            .model(GPT4O_MODEL)
                            .maxTokens(16384)
                            .build())
                    .call()
                    .content();

            return parseGrammarResponse(rawResponse, chunkIndex, System.currentTimeMillis() - t0);

        } catch (Exception e) {
            log.error("GPT-4o grammar extraction failed for chunk {}: {}", chunkIndex, e.getMessage());
            return emptyResult(chunkIndex, System.currentTimeMillis() - t0);
        }
    }

    // ── Internal helpers ───────────────────────────────────────────────────────

    /** Build a Spring AI UserMessage with text + JPEG images for the GPT-4o path. */
    private UserMessage buildGpt4oMessage(String text, List<byte[]> images) {
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
            log.error("Failed to parse vision vocabulary response for chunk {}: {}",
                    chunkIndex, e.getMessage());
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
            log.error("Failed to parse vision grammar response for chunk {}: {}",
                    chunkIndex, e.getMessage());
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
