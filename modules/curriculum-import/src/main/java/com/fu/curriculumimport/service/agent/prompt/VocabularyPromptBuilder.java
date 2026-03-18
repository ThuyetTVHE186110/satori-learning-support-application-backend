package com.fu.curriculumimport.service.agent.prompt;

import com.fu.curriculumimport.service.embedding.RagContext;
import com.fu.curriculumimport.service.ingestion.DocumentChunk;
import com.fu.curriculumimport.service.ingestion.MnNSectionSplitter;
import com.fu.curriculumimport.service.ingestion.OcrTextPreprocessor;

/**
 * Builds system and user prompts for {@code VocabularyExtractionAgent}.
 *
 * <p>Extracted from the agent so the class containing LLM-call logic stays concise.
 * All members are package-private static — no Spring dependency, fully testable without
 * application context.
 */
public final class VocabularyPromptBuilder {

    private VocabularyPromptBuilder() {}

    // ── System prompt ─────────────────────────────────────────────────────────

    public static final String SYSTEM_PROMPT = """
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

    /** Suffix appended to the system prompt on re-extraction attempts. */
    public static final String REEXTRACTION_SYSTEM_SUFFIX =
            "\nIMPORTANT: You MUST fix every issue noted below.";

    // ── Extraction prompt template ─────────────────────────────────────────────

    private static final String EXTRACTION_TEMPLATE = """
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

    // ── Context block templates ────────────────────────────────────────────────

    private static final String RAG_CONTEXT_TEMPLATE = """

            VOCABULARY ALREADY IN DATABASE (skip duplicates, maintain consistency):
            %s
            """;

    private static final String FULL_PAGE_CONTEXT_TEMPLATE = """

            ─── FULL LESSON PAGE CONTEXT (adjacent sections — use for cross-referencing) ──
            %s
            ─────────────────────────────────────────────────────────────────────────────────
            Note: The section to extract FROM is the "CONTENT" block above.
            Use this full-page context only to resolve ambiguities and verify readings.
            """;

    // ── Public builder API ────────────────────────────────────────────────────

    /**
     * Build the user prompt for a vocabulary extraction call.
     *
     * @param chunk           document chunk to extract from
     * @param jlptLevel       target JLPT level string (e.g. "N5")
     * @param ragSection      pre-formatted RAG context block (may be empty)
     * @param feedbackSection validation feedback for re-extraction (may be empty)
     * @return user prompt string ready to pass to the LLM
     */
    public static String buildExtractionPrompt(DocumentChunk chunk, String jlptLevel,
                                               String ragSection, String feedbackSection) {
        String lessonTopic = inferLessonTopic(chunk);
        String sourceType = describeSourceType(chunk.getChunkType());
        String sectionHint = buildSectionHint(chunk);
        String fullPageSection = buildFullPageContextSection(chunk);

        String safeContent = OcrTextPreprocessor.truncateToTokenBudget(
                chunk.getContent(), OcrTextPreprocessor.MAX_CONTENT_TOKENS);

        String contextBlocks = sectionHint + fullPageSection + ragSection + feedbackSection;

        return String.format(EXTRACTION_TEMPLATE,
                chunk.getPageStart(), chunk.getPageEnd(),
                jlptLevel,
                lessonTopic,
                sourceType,
                contextBlocks,
                safeContent,
                buildMeaningHint(chunk),
                jlptLevel);
    }

    /**
     * Format a RAG context for vocabulary.
     * Returns an empty string when no relevant context is available.
     */
    public static String formatRagContext(RagContext ragContext) {
        if (ragContext == null || !ragContext.hasContext()) return "";
        StringBuilder sb = new StringBuilder();
        for (RagContext.SimilarItem item : ragContext.getSimilarItems()) {
            if ("VOCABULARY".equals(item.getContentType())) {
                sb.append("- ").append(item.getTextContent()).append("\n");
            }
        }
        return sb.isEmpty() ? "" : String.format(RAG_CONTEXT_TEMPLATE, sb);
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    /**
     * Inject the full lesson page context when the chunk is a section sub-chunk.
     * Only added when the page context is meaningfully larger than the chunk itself.
     */
    private static String buildFullPageContextSection(DocumentChunk chunk) {
        String fullCtx = chunk.getFullPageContext();
        String content = chunk.getContent();
        if (fullCtx == null || fullCtx.isBlank() || fullCtx.equals(content)) return "";
        String trimmed = OcrTextPreprocessor.truncateToTokenBudget(
                fullCtx, OcrTextPreprocessor.MAX_PAGE_CONTEXT_TOKENS);
        return String.format(FULL_PAGE_CONTEXT_TEMPLATE, trimmed);
    }

    /**
     * Build a structural section hint from the chunk's MnN section type.
     * Tells the LLM exactly what kind of section it is reading.
     */
    private static String buildSectionHint(DocumentChunk chunk) {
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
     * Priority: lesson title from boundary detector → PPTX title line → first 120 chars.
     */
    private static String inferLessonTopic(DocumentChunk chunk) {
        if (chunk.getLessonTitle() != null && !chunk.getLessonTitle().isBlank()) {
            return chunk.getLessonTitle();
        }
        String content = chunk.getContent();
        if (content == null) return "unknown";

        String firstLine = content.lines().findFirst().orElse("").trim();
        if (firstLine.contains("タイトル:")) {
            return firstLine.substring(firstLine.indexOf("タイトル:") + 5).trim();
        }
        if (firstLine.contains("[SLIDE")) {
            return firstLine;
        }
        return content.substring(0, Math.min(120, content.length())).replaceAll("\\s+", " ").trim();
    }

    private static String describeSourceType(String chunkType) {
        if (chunkType == null) return "text-based PDF";
        return switch (chunkType) {
            case "SLIDE"   -> "PowerPoint slide (vocabulary list may NOT include meanings — infer them)";
            case "SCANNED" -> "scanned PDF page extracted via OCR (text quality may be imperfect)";
            default        -> "text-based PDF";
        };
    }

    /**
     * Additional hint injected when the source is a slide (meanings likely absent).
     */
    private static String buildMeaningHint(DocumentChunk chunk) {
        if ("SLIDE".equals(chunk.getChunkType())) {
            return """
                    ⚠ IMPORTANT: This is a PowerPoint slide. \
                    The vocabulary list likely has NO meanings. \
                    For every word without an explicit meaning, set "meaningSource": "AI_INFERRED" \
                    and generate an accurate meaning based on the lesson topic and JLPT level.""";
        }
        return "";
    }
}
