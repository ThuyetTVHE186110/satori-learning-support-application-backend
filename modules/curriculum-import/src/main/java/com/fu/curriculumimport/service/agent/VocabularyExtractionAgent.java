package com.fu.curriculumimport.service.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fu.curriculumimport.service.embedding.RagContext;
import com.fu.curriculumimport.service.ingestion.DocumentChunk;
import com.fu.curriculumimport.service.ingestion.MnNSectionSplitter;
import com.fu.curriculumimport.service.ingestion.MnNTableParser;
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
 * Specialized Vocabulary Extraction Agent.
 *
 * <h3>Key capabilities</h3>
 * <ul>
 *   <li>Extracts Japanese vocabulary (kanji/kana), reading (hiragana), romaji,
 *       part of speech, example sentences.</li>
 *   <li><b>Japanese-only slides:</b> when a slide or document page lists words
 *       WITHOUT meanings (common in PPTX slides), the agent infers Vietnamese and
 *       English meanings from the lesson context, JLPT level, and surrounding
 *       content.  All inferred meanings are tagged {@code meaningSource=AI_INFERRED}
 *       so managers can verify them during the review step.</li>
 *   <li>Supports Corrective-RAG re-extraction with structured feedback.</li>
 * </ul>
 *
 * <p>Reference: Agentic RAG specialized worker pattern (Asai et al. 2023 Self-RAG)
 */
@Service
@Slf4j
public class VocabularyExtractionAgent {

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;

    public VocabularyExtractionAgent(OpenAiChatModel chatModel, ObjectMapper objectMapper) {
        this.chatClient = ChatClient.builder(chatModel).build();
        this.objectMapper = objectMapper;
    }

    // ── System prompt ────────────────────────────────────────────────────────

    private static final String SYSTEM_PROMPT = """
            You are an expert Japanese vocabulary extractor for a language-learning platform.
            Your input is Japanese textbook content — it may be a text-based PDF, a scanned PDF, or a PowerPoint slide.

            ═══ CRITICAL RULE — MEANINGS ═══
            The source text may NOT contain Vietnamese or English meanings.
            This is especially common with PowerPoint slides that only list Japanese words.

            • INLINE MEANING PATTERN (most important rule):
              Vietnamese-edition textbooks often attach the meaning directly after the
              Japanese word inside full-width parentheses （）:
                行きます（đi）        食べます（ăn）        先生（giáo viên）
              When you see this pattern:
                - japaneseText = the Japanese part ONLY → "行きます"  (NO brackets, NO meaning)
                - meaningVi    = the content inside （）   → "đi"
                - meaningSource = "SOURCE_INLINE"
              NEVER include the （…） portion in japaneseText. Ever.

            • EXCEPTION — furigana (kana-only content inside （）):
              If the content inside （） is hiragana or katakana only, it is a furigana
              reading, NOT a meaning.  Extract it as the "reading" field instead.
                先生（せんせい）  → japaneseText="先生", reading="せんせい"  (NOT a meaning)

            • If a meaning IS present in the source text in a separate column or line
              → extract it exactly. Set "meaningSource": "SOURCE".

            • If a meaning is NOT present → infer the most accurate meaning using:
                1. The lesson topic / slide title (provided in the prompt).
                2. The JLPT level context.
                3. Your knowledge of standard Japanese vocabulary.
              Set "meaningSource": "AI_INFERRED".

            You MUST always provide both meaningVi (Vietnamese) and meaningEn (English).
            NEVER leave meanings empty. NEVER write "unknown" or "N/A".

            ═══ OTHER RULES ═══
            • Every Japanese word MUST have a hiragana reading (ふりがな).
            • Part of speech must be precise (noun/verb/i-adjective/na-adjective/
              adverb/particle/conjunction/interjection/counter/prefix/suffix/expression).
            • Include example sentences where possible; derive from the slide if present,
              otherwise construct a natural example appropriate for the JLPT level.
            • Respond ONLY with valid JSON — no markdown, no explanations.

            ═══ COUNTRY-LANGUAGE TABLE PATTERN ═══
            Minna no Nihongo has a 4-column country/language reference table:
              Col 0: country name (国名)  | Col 1: nationality (〜人) | Col 2: empty | Col 3: language (〜語)
              アメリカ ( Mý )             |                           |              | 英語 ( tiêng Anh )
              かんこく                    | かんこくじん               |              | かんこく ご

            CRITICAL RULES for this pattern:
            • Col 0 (country) and Col 3 (language) are SEPARATE vocabulary items — extract BOTH.
            • NEVER use the language name (Col 3) as the meaning/definition of the country (Col 0).
            • If Col 1 (nationality) is non-empty, extract it as a THIRD vocabulary item.
            • Each item gets its own entry in the "vocabulary" array.
            • The half-width parentheses pattern word ( meaning ) is the inline Vietnamese meaning.
            """;

    // ── Extraction prompt ────────────────────────────────────────────────────

    private static final String EXTRACTION_PROMPT = """
            Extract ALL vocabulary items from the following Japanese learning content.

            Slide / Page: %d-%d
            JLPT Level: %s
            Lesson topic / title: %s
            Source type: %s
            %s

            ─── CONTENT ────────────────────────────────────────────────────────
            %s
            ─────────────────────────────────────────────────────────────────────

            %s

            Return this exact JSON structure:
            {
              "vocabulary": [
                {
                  "japaneseText": "待合室",
                  "reading": "まちあいしつ",
                  "romaji": "machiaishitsu",
                  "meaningVi": "Phòng chờ",
                  "meaningEn": "Waiting room",
                  "partOfSpeech": "noun",
                  "jlptLevel": "%s",
                  "kanjiWriting": "待合室",
                  "exampleSentences": [
                    {
                      "jp": "待合室で少々お待ちください。",
                      "vi": "Vui lòng chờ một chút ở phòng chờ.",
                      "en": "Please wait a moment in the waiting room."
                    }
                  ],
                  "isKeyWord": true,
                  "meaningSource": "SOURCE"
                }
              ],
              "confidence": 0.9
            }

            Use "meaningSource": "SOURCE" if the meaning was in the content above.
            Use "meaningSource": "AI_INFERRED" if you inferred the meaning from context.
            """;

    // ── RAG context template ─────────────────────────────────────────────────

    private static final String RAG_CONTEXT_TEMPLATE = """

            VOCABULARY ALREADY IN DATABASE (skip duplicates, maintain consistency):
            %s
            """;

    // ── Full page context template (Page-Index RAG) ──────────────────────────

    /**
     * When a section chunk is smaller than the full lesson page, inject the full
     * lesson content so the agent can see words in adjacent sections (examples,
     * dialogue) that should be extracted alongside the vocabulary list section.
     */
    private static final String FULL_PAGE_CONTEXT_TEMPLATE = """

            ─── FULL LESSON PAGE CONTEXT (adjacent sections — use for cross-referencing) ──
            %s
            ─────────────────────────────────────────────────────────────────────────────────
            Note: The section to extract FROM is the "CONTENT" block above.
            Use this full-page context only to resolve ambiguities and verify readings.
            """;


    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Extract vocabulary from a chunk, with optional RAG context.
     *
     * <p>Fast path: when the OCR service has detected structured table rows in
     * the chunk (see {@link DocumentChunk#getStructuredTableRows()}), the rows
     * are parsed directly by {@link MnNTableParser} — no LLM call needed.
     * Falls back to LLM extraction when table parsing returns no results.
     */
    public ExtractionResult extractVocabulary(DocumentChunk chunk, String jlptLevel,
                                               RagContext ragContext) {
        long t0 = System.currentTimeMillis();

        // ── Fast path: structured table rows from OCR ─────────────────────────
        if (chunk.getStructuredTableRows() != null && !chunk.getStructuredTableRows().isEmpty()) {
            List<ExtractionResult.ExtractedVocabulary> tableVocab =
                    MnNTableParser.parse(chunk.getStructuredTableRows(), jlptLevel);
            if (!tableVocab.isEmpty()) {
                log.info("Chunk {}: table parser extracted {} vocab items (no LLM call)",
                        chunk.getChunkIndex(), tableVocab.size());
                return ExtractionResult.builder()
                        .chunkIndex(chunk.getChunkIndex())
                        .confidence(0.95)
                        .tokensUsed(0)
                        .processingTimeMs(System.currentTimeMillis() - t0)
                        .lessons(Collections.emptyList())
                        .vocabulary(tableVocab)
                        .grammarPoints(Collections.emptyList())
                        .build();
            }
            log.debug("Chunk {}: table parse failed (low rate), falling back to LLM", chunk.getChunkIndex());
        }

        // ── LLM path ──────────────────────────────────────────────────────────

        // Skip trivial content (noise-only OCR page) before calling LLM
        if (OcrTextPreprocessor.isTrivial(chunk.getContent())) {
            log.info("Chunk {}: trivial content — skipping vocabulary LLM extraction",
                    chunk.getChunkIndex());
            return emptyResult(chunk.getChunkIndex(), System.currentTimeMillis() - t0);
        }

        try {
            String ragSection = formatRagContext(ragContext);
            String prompt = buildPrompt(chunk, jlptLevel, ragSection, "");

            String response = chatClient.prompt()
                    .system(SYSTEM_PROMPT)
                    .user(prompt)
                    .options(OpenAiChatOptions.builder().maxTokens(4096).build())
                    .call()
                    .content();

            return parseResponse(response, chunk.getChunkIndex(), System.currentTimeMillis() - t0);
        } catch (Exception e) {
            log.error("Vocabulary extraction failed for chunk {}: {}", chunk.getChunkIndex(), e.getMessage());
            return emptyResult(chunk.getChunkIndex(), System.currentTimeMillis() - t0);
        }
    }

    /**
     * Re-extract with Corrective-RAG validation feedback.
     */
    public ExtractionResult reExtractWithFeedback(DocumentChunk chunk, String jlptLevel,
                                                   RagContext ragContext,
                                                   String validationFeedback) {
        long t0 = System.currentTimeMillis();
        try {
            String ragSection = formatRagContext(ragContext);
            String feedbackSection = "\n\nPREVIOUS EXTRACTION ISSUES — please fix ALL of these:\n"
                    + validationFeedback;

            String prompt = buildPrompt(chunk, jlptLevel, ragSection, feedbackSection);

            String response = chatClient.prompt()
                    .system(SYSTEM_PROMPT + "\nIMPORTANT: You MUST fix every issue noted below.")
                    .user(prompt)
                    .options(OpenAiChatOptions.builder().maxTokens(4096).build())
                    .call()
                    .content();

            return parseResponse(response, chunk.getChunkIndex(), System.currentTimeMillis() - t0);
        } catch (Exception e) {
            log.error("Vocabulary re-extraction failed for chunk {}: {}", chunk.getChunkIndex(), e.getMessage());
            return emptyResult(chunk.getChunkIndex(), System.currentTimeMillis() - t0);
        }
    }

    // ── Prompt construction ──────────────────────────────────────────────────

    private String buildPrompt(DocumentChunk chunk, String jlptLevel,
                                String ragSection, String feedbackSection) {
        String lessonTopic = inferLessonTopic(chunk);
        String sourceType = describeSourceType(chunk.getChunkType());
        String sectionHint = buildSectionHint(chunk);
        String fullPageSection = buildFullPageContextSection(chunk);

        // Cap content to token budget so the AI response is never truncated
        String safeContent = OcrTextPreprocessor.truncateToTokenBudget(
                chunk.getContent(), OcrTextPreprocessor.MAX_CONTENT_TOKENS);

        return String.format(EXTRACTION_PROMPT,
                chunk.getPageStart(), chunk.getPageEnd(),
                jlptLevel,
                lessonTopic,
                sourceType,
                sectionHint + fullPageSection + ragSection + feedbackSection,
                safeContent,
                buildMeaningHint(chunk),
                jlptLevel);
    }

    /**
     * Build a full-page context block when the chunk is a section sub-chunk.
     * Injects the full lesson text (all sections) so the agent can cross-reference
     * readings and meanings found in grammar/example sections on the same page.
     */
    private String buildFullPageContextSection(DocumentChunk chunk) {
        String fullCtx = chunk.getFullPageContext();
        String content  = chunk.getContent();
        // Only inject when page context is meaningfully larger than the chunk itself
        if (fullCtx == null || fullCtx.isBlank() || fullCtx.equals(content)) return "";
        String trimmed = OcrTextPreprocessor.truncateToTokenBudget(
                fullCtx, OcrTextPreprocessor.MAX_PAGE_CONTEXT_TOKENS);
        return String.format(FULL_PAGE_CONTEXT_TEMPLATE, trimmed);
    }

    /**
     * Build a structural section hint injected before the content.
     * Tells the LLM exactly what kind of section it is reading so it can
     * apply the right extraction strategy without guessing.
     */
    private String buildSectionHint(DocumentChunk chunk) {
        String sectionType = chunk.getSectionType();
        if (sectionType == null || MnNSectionSplitter.UNKNOWN.equals(sectionType)) return "";
        return switch (sectionType) {
            case MnNSectionSplitter.VOCABULARY ->
                    "\n⚠ SECTION TYPE: VOCABULARY LIST (語彙 / Từ vựng)\n" +
                    "This section contains the lesson's vocabulary list. " +
                    "Each entry has a Japanese word, its reading, and meaning. " +
                    "Extract EVERY word — do not skip any.\n";
            case MnNSectionSplitter.EXAMPLE ->
                    "\n⚠ SECTION TYPE: EXAMPLE SENTENCES (例文 / Ví dụ)\n" +
                    "This section contains illustrative example sentences that use vocabulary " +
                    "from this lesson. Extract vocabulary items that appear in these examples " +
                    "if they are not already covered by the vocabulary list section.\n";
            case MnNSectionSplitter.DIALOG ->
                    "\n⚠ SECTION TYPE: DIALOGUE (会話 / Hội thoại)\n" +
                    "This section is a scripted dialogue. Extract key vocabulary items " +
                    "that appear in the dialogue and are relevant to the lesson theme.\n";
            default -> "";
        };
    }

    /**
     * Determine the lesson topic/title for use in the extraction prompt.
     *
     * <p>Priority:
     * <ol>
     *   <li>Lesson title detected by boundary detector (e.g. "Bài 1").</li>
     *   <li>PPTX slide title ({@code タイトル:} prefix).</li>
     *   <li>First 120 characters of the chunk content.</li>
     * </ol>
     */
    private String inferLessonTopic(DocumentChunk chunk) {
        // 1. Lesson-aware chunking provides the title directly
        if (chunk.getLessonTitle() != null && !chunk.getLessonTitle().isBlank()) {
            return chunk.getLessonTitle();
        }

        String content = chunk.getContent();
        if (content == null) return "unknown";

        // 2. PPTX structured format: first line contains the title
        String firstLine = content.lines().findFirst().orElse("").trim();
        if (firstLine.contains("タイトル:")) {
            return firstLine.substring(firstLine.indexOf("タイトル:") + 5).trim();
        }
        if (firstLine.contains("[SLIDE")) {
            return firstLine;
        }

        // 3. Fallback: first 120 chars
        return content.substring(0, Math.min(120, content.length())).replaceAll("\\s+", " ").trim();
    }

    private String describeSourceType(String chunkType) {
        if (chunkType == null) return "text-based PDF";
        return switch (chunkType) {
            case "SLIDE"   -> "PowerPoint slide (vocabulary list may NOT include meanings — infer them)";
            case "SCANNED" -> "scanned PDF page extracted via OCR (text quality may be imperfect)";
            default        -> "text-based PDF";
        };
    }

    /**
     * Additional hint injected into the prompt when the chunk source is a slide
     * (likely no meanings → the AI needs an explicit reminder).
     */
    private String buildMeaningHint(DocumentChunk chunk) {
        if ("SLIDE".equals(chunk.getChunkType())) {
            return """
                    ⚠ IMPORTANT: This is a PowerPoint slide. \
                    The vocabulary list likely has NO meanings. \
                    For every word without an explicit meaning, set "meaningSource": "AI_INFERRED" \
                    and generate an accurate meaning based on the lesson topic and JLPT level.""";
        }
        return "";
    }

    // ── Response parsing ─────────────────────────────────────────────────────

    private ExtractionResult parseResponse(String json, int chunkIndex, long ms) {
        try {
            Map<String, Object> parsed = objectMapper.readValue(LlmResponseUtils.cleanJson(json), new TypeReference<>() {});
            double confidence = ((Number) parsed.getOrDefault("confidence", 0.5)).doubleValue();

            List<ExtractionResult.ExtractedVocabulary> vocab = parseField(
                    parsed, "vocabulary", new TypeReference<>() {});

            // Default meaningSource to AI_INFERRED for any item that is missing it
            if (vocab != null) {
                vocab.forEach(v -> {
                    if (v.getMeaningSource() == null || v.getMeaningSource().isBlank()) {
                        v.setMeaningSource("AI_INFERRED");
                    }
                });
            }

            return ExtractionResult.builder()
                    .chunkIndex(chunkIndex)
                    .confidence(confidence)
                    .tokensUsed(LlmResponseUtils.estimateTokens(json))
                    .processingTimeMs(ms)
                    .lessons(Collections.emptyList())
                    .vocabulary(vocab != null ? vocab : Collections.emptyList())
                    .grammarPoints(Collections.emptyList())
                    .build();

        } catch (JsonProcessingException e) {
            log.error("Failed to parse vocab response for chunk {}: {}", chunkIndex, e.getMessage());
            return emptyResult(chunkIndex, ms);
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

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
            return objectMapper.readValue(objectMapper.writeValueAsString(parsed.get(field)), type);
        } catch (JsonProcessingException e) {
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
