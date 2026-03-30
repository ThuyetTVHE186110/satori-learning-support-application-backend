package com.fu.curriculumimport.service.vision;

import com.fu.curriculumimport.config.OcrProperties;
import com.google.genai.Client;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.Part;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Gemini 2.5 Flash vision client for structured page extraction (Vision Tier 2).
 *
 * <p>Replaces GPT-4o Vision to reduce per-page cost by ~95% while maintaining
 * extraction quality on Minna no Nihongo vocabulary and grammar pages.
 *
 * <h3>Key features over GPT-4o path</h3>
 * <ul>
 *   <li><b>JSON mode</b> — {@code responseMimeType="application/json"} instructs Gemini
 *       to return valid JSON. Combined with the structured prompt templates this is
 *       sufficient; {@code LlmResponseUtils.cleanJson()} handles any stray markdown fences.</li>
 *   <li><b>Multimodal</b> — up to 4 JPEG page images per call via
 *       {@link Part#fromBytes(byte[], String)}.</li>
 *   <li><b>System instruction</b> — the existing {@link VisionExtractionPrompts} system
 *       prompts are passed as {@code systemInstruction} in {@link GenerateContentConfig}.</li>
 * </ul>
 *
 * <h3>Activation</h3>
 * <p>Set {@code curriculum-import.ocr.gemini-api-key} in {@code application.yml}
 * (or {@code GEMINI_API_KEY} env var). When blank, {@link VisionPageExtractor}
 * falls back to the GPT-4o path automatically.
 *
 * <h3>Model</h3>
 * <p>Uses {@code gemini-2.5-flash} — the current recommended Gemini workhorse model
 * as of the google-genai SDK docs. ({@code gemini-2.0-flash} is deprecated.)
 */
@Slf4j
@Component
public class GeminiVisionClient {

    /** Current Gemini model for multimodal extraction (docs-confirmed, 2.0-flash is deprecated). */
    static final String GEMINI_MODEL = "gemini-2.5-flash";

    // ── Text reconstruction prompts ──────────────────────────────────────────

    /**
     * System instruction for plain-text OCR reconstruction.
     * Gemini acts as a faithful text reproducer, not an extractor.
     */
    private static final String TEXT_RECONSTRUCTION_SYSTEM =
            "You are a precise OCR engine for Japanese language textbook pages. "
            + "Output the page text exactly as written, preserving all scripts and formatting. "
            + "Do NOT add any commentary, markdown, or extra formatting.";

    /**
     * User prompt for text reconstruction of Minna no Nihongo pages.
     * Emphasises preserving Japanese characters inside brackets, which Google Vision
     * systematically corrupts in mixed Vietnamese-Japanese text.
     */
    private static final String TEXT_RECONSTRUCTION_PROMPT = """
            Tái tạo chính xác toàn bộ văn bản hiển thị trên các trang sách này.
            Đây là sách "Minna no Nihongo" (bản dịch và giải thích ngữ pháp tiếng Việt).

            QUY TẮC:
            - Giữ nguyên 100% ký tự tiếng Nhật (hiragana ひらがな, katakana カタカナ, kanji 漢字)
            - Giữ nguyên 100% văn bản tiếng Việt (bao gồm dấu thanh)
            - Nội dung trong ngoặc vuông [] LUÔN chứa ký tự tiếng Nhật — ví dụ: [は], [です], [か], [も], [の], [さん]
            - Giữ đúng số thứ tự: 1., 2., 1), 2), ①, ②, v.v.
            - Giữ đúng cấu trúc trang: tiêu đề mục, phần giải thích, câu ví dụ, [Chú ý]
            - Furigana nhỏ phía trên kanji → ghi dạng 漢字[ふりがな] (kanji trước, furigana trong ngoặc vuông)
            - Mỗi trang cách nhau bằng một dòng trống
            - CHỈ xuất văn bản thuần — KHÔNG thêm markdown, tiêu đề bổ sung, hay bình luận
            """;

    /**
     * Maximum page images per Gemini call.
     * A4 at 300 DPI encodes to ~200–500 KB JPEG; 8 pages ≈ 2–4 MB, well within the
     * Gemini 20 MB inline limit and 1M-token context window.
     */
    private static final int MAX_IMAGES_PER_CALL = 8;

    private final Client geminiClient;
    private final boolean available;

    public GeminiVisionClient(OcrProperties props) {
        String apiKey = props.getGeminiApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            this.geminiClient = null;
            this.available = false;
            log.info("GeminiVisionClient disabled (gemini-api-key not set) — GPT-4o path active");
        } else {
            // Client.builder().apiKey() uses the Gemini Developer API endpoint.
            // For Vertex AI backend, use .vertexAI(true) instead.
            this.geminiClient = Client.builder().apiKey(apiKey).build();
            this.available = true;
            log.info("GeminiVisionClient initialised (model: {})", GEMINI_MODEL);
        }
    }

    /** Returns true when the Gemini API key is configured and the client is ready. */
    public boolean isAvailable() {
        return available;
    }

    /**
     * Extract vocabulary from page image(s) using Gemini Vision with JSON schema enforcement.
     *
     * <p>The response schema mirrors the {@link VisionExtractionPrompts#VOCABULARY_USER_TEMPLATE}
     * JSON structure so Gemini outputs valid JSON directly — no markdown stripping needed.
     *
     * @param pageImages  JPEG-encoded page images (up to {@value #MAX_IMAGES_PER_CALL})
     * @param lessonTopic lesson title / topic for contextual grounding
     * @param jlptLevel   JLPT level string (e.g. "N5")
     * @return raw JSON string matching the vocabulary schema; empty string on failure
     */
    public String extractVocabularyJson(List<byte[]> pageImages,
                                        String lessonTopic, String jlptLevel) {
        if (!available) return "";
        String level = jlptLevel   != null ? jlptLevel   : "N5";
        String topic = lessonTopic != null ? lessonTopic : "unknown";
        String userText = String.format(VisionExtractionPrompts.VOCABULARY_USER_TEMPLATE,
                topic, level, level);
        GenerateContentConfig config = GenerateContentConfig.builder()
                .responseMimeType("application/json")
                .systemInstruction(Content.fromParts(
                        Part.fromText(VisionExtractionPrompts.VOCABULARY_SYSTEM_PROMPT)))
                .build();
        return callInBatches(pageImages, userText, config, "vocab");
    }

    /**
     * Extract grammar patterns from page image(s) using Gemini Vision with JSON schema enforcement.
     *
     * @param pageImages  JPEG-encoded page images (up to {@value #MAX_IMAGES_PER_CALL})
     * @param lessonTopic lesson title / topic for contextual grounding
     * @param jlptLevel   JLPT level string (e.g. "N5")
     * @return raw JSON string matching the grammar schema; empty string on failure
     */
    public String extractGrammarJson(List<byte[]> pageImages,
                                     String lessonTopic, String jlptLevel) {
        if (!available) return "";
        String level = jlptLevel   != null ? jlptLevel   : "N5";
        String topic = lessonTopic != null ? lessonTopic : "unknown";
        String userText = String.format(VisionExtractionPrompts.GRAMMAR_USER_TEMPLATE,
                topic, level, level);
        GenerateContentConfig config = GenerateContentConfig.builder()
                .responseMimeType("application/json")
                .systemInstruction(Content.fromParts(
                        Part.fromText(VisionExtractionPrompts.GRAMMAR_SYSTEM_PROMPT)))
                .build();
        return callInBatches(pageImages, userText, config, "grammar");
    }

    /**
     * Reconstruct clean text from page images using Gemini vision.
     *
     * <p>Designed for mixed Japanese-Vietnamese textbook pages where Google Vision /
     * Document AI OCR systematically corrupts isolated Japanese characters inside
     * brackets (e.g. {@code [は]} becomes {@code [t]}, {@code [です]} becomes
     * {@code [C#]}). Gemini's multimodal understanding reads the actual glyphs and
     * produces faithful text.
     *
     * <p>Unlike {@link #extractVocabularyJson} / {@link #extractGrammarJson}, this
     * method returns <b>plain text</b> (not JSON). The output replaces the corrupted
     * OCR text in chunk content before section splitting and extraction.
     *
     * @param pageImages JPEG-encoded page images, in page order
     * @return reconstructed plain text; empty string on failure or when Gemini is unavailable
     */
    public String reconstructText(List<byte[]> pageImages) {
        if (!available || pageImages.isEmpty()) return "";

        GenerateContentConfig config = GenerateContentConfig.builder()
                .systemInstruction(Content.fromParts(Part.fromText(TEXT_RECONSTRUCTION_SYSTEM)))
                .build(); // no responseMimeType — plain text output

        StringBuilder result = new StringBuilder();
        for (int start = 0; start < pageImages.size(); start += MAX_IMAGES_PER_CALL) {
            int end = Math.min(start + MAX_IMAGES_PER_CALL, pageImages.size());
            List<byte[]> batch = pageImages.subList(start, end);
            try {
                Content content = buildMultimodalContent(TEXT_RECONSTRUCTION_PROMPT, batch);
                GenerateContentResponse response = geminiClient.models.generateContent(
                        GEMINI_MODEL, content, config);
                String text = response.text();
                if (text != null && !text.isBlank()) {
                    if (!result.isEmpty()) result.append("\n\n");
                    result.append(text.strip());
                }
                log.debug("Gemini text reconstruction batch {}-{}: {} chars",
                        start + 1, end, text != null ? text.length() : 0);
            } catch (Exception e) {
                log.warn("Gemini text reconstruction failed (batch {}-{}): {}",
                        start + 1, end, e.getMessage());
            }
        }
        return result.toString();
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    /**
     * Call Gemini in batches of {@value #MAX_IMAGES_PER_CALL} images and merge the
     * resulting JSON arrays.
     *
     * <p>When a chunk contains more images than the per-call limit (e.g. 6-image lesson
     * chunk), a single call would silently drop the trailing pages. This method splits
     * the images into batches, calls Gemini for each batch, and concatenates the item
     * arrays from each response into a single JSON object that the caller can parse.
     *
     * <p>If only one batch is needed (≤ {@value #MAX_IMAGES_PER_CALL} images), the raw
     * Gemini JSON is returned as-is without parsing/re-serialising.
     *
     * @param images    all JPEG page images for the chunk
     * @param userText  the formatted user prompt
     * @param config    Gemini generation config (shared across batches)
     * @param kind      "vocab" or "grammar" — used for determining the array key in JSON
     * @return merged JSON string, or empty string on failure
     */
    private String callInBatches(List<byte[]> images, String userText,
                                  GenerateContentConfig config, String kind) {
        if (images.isEmpty()) return "";

        // Determine the JSON array key expected by the parser
        String arrayKey = "vocab".equals(kind) ? "vocabulary" : "grammarPoints";

        List<String> batchJsons = new ArrayList<>();
        for (int start = 0; start < images.size(); start += MAX_IMAGES_PER_CALL) {
            int end = Math.min(start + MAX_IMAGES_PER_CALL, images.size());
            List<byte[]> batch = images.subList(start, end);
            try {
                Content content = buildMultimodalContent(userText, batch);
                GenerateContentResponse response = geminiClient.models.generateContent(
                        GEMINI_MODEL, content, config);
                String json = response.text();
                log.debug("Gemini {} extraction: {} chars JSON, images={}",
                        kind, json != null ? json.length() : 0, batch.size());
                if (json != null && !json.isBlank()) {
                    batchJsons.add(json);
                }
            } catch (Exception e) {
                log.error("Gemini {} extraction failed (batch {}-{}): {}", kind, start + 1, end, e.getMessage(), e);
            }
        }

        if (batchJsons.isEmpty()) return "";
        if (batchJsons.size() == 1) return batchJsons.get(0);

        // Merge multiple batch JSONs: extract each array and concatenate items.
        // Simple string-based merge to avoid a full JSON parse dependency here.
        // Format: {"<arrayKey>":[...all items...],"confidence":<max>}
        StringBuilder merged = new StringBuilder("{\"").append(arrayKey).append("\":[");
        double maxConfidence = 0.0;
        boolean firstItem = true;
        for (String batchJson : batchJsons) {
            // Extract the array content between the first '[' and last ']' of the array key
            int arrayStart = batchJson.indexOf("\"" + arrayKey + "\"");
            if (arrayStart < 0) continue;
            int bracketOpen = batchJson.indexOf('[', arrayStart);
            int bracketClose = batchJson.lastIndexOf(']');
            if (bracketOpen < 0 || bracketClose <= bracketOpen) continue;

            String arrayContent = batchJson.substring(bracketOpen + 1, bracketClose).strip();
            if (!arrayContent.isEmpty()) {
                if (!firstItem) merged.append(",");
                merged.append(arrayContent);
                firstItem = false;
            }
            // Extract confidence
            int confIdx = batchJson.indexOf("\"confidence\"");
            if (confIdx >= 0) {
                int colon = batchJson.indexOf(':', confIdx);
                int end = batchJson.indexOf('}', colon);
                if (colon >= 0 && end > colon) {
                    try {
                        double c = Double.parseDouble(batchJson.substring(colon + 1, end).strip());
                        maxConfidence = Math.max(maxConfidence, c);
                    } catch (NumberFormatException ignored) {}
                }
            }
        }
        merged.append("],\"confidence\":").append(maxConfidence).append("}");
        return merged.toString();
    }

    /**
     * Build a multimodal {@link Content} with a text prompt followed by inline JPEG images.
     */
    private Content buildMultimodalContent(String text, List<byte[]> images) {
        List<Part> parts = new ArrayList<>(1 + images.size());
        parts.add(Part.fromText(text));
        for (byte[] image : images) {
            parts.add(Part.fromBytes(image, "image/jpeg"));
        }
        return Content.fromParts(parts.toArray(new Part[0]));
    }

}
