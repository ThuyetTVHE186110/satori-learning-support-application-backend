package com.fu.curriculumimport.service.agent.prompt;

import com.fu.common.ai.PromptFragment;
import com.fu.curriculumimport.service.embedding.RagContext;
import com.fu.curriculumimport.service.ingestion.DocumentChunk;
import com.fu.curriculumimport.service.ingestion.MnNSectionSplitter;
import com.fu.curriculumimport.service.ingestion.OcrTextPreprocessor;

/**
 * Builds system and user prompts for {@code GrammarExtractionAgent}.
 *
 * <p>Extracted from the agent so the class containing LLM-call logic stays concise.
 * All members are package-private static — no Spring dependency, fully testable without
 * application context.
 */
public final class GrammarPromptBuilder {

    private GrammarPromptBuilder() {}

    // ── System prompt ─────────────────────────────────────────────────────────

    public static final String SYSTEM_PROMPT =
            "You are an expert Japanese grammar analyzer for language learning.\n" +
            "Extract ALL grammar points from the given textbook content with detailed explanations.\n\n" +
            PromptFragment.JSON_ONLY + "\n\n" +
            """
            Quality requirements:
            - Each grammar pattern must have clear structure notation
            - Explanations must be in BOTH Vietnamese and English
            - Provide at least one example sentence per pattern
            - Identify the correct grammar category
            - Note common mistakes learners make
            - Flag whether each pattern is a key grammar point
            """;

    /** Suffix appended to the system prompt on re-extraction attempts. */
    public static final String REEXTRACTION_SYSTEM_SUFFIX =
            "\nIMPORTANT: Fix the issues noted in the feedback.";

    // ── Extraction prompt template ─────────────────────────────────────────────

    private static final String EXTRACTION_TEMPLATE = """
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

    // ── Context block templates ────────────────────────────────────────────────

    private static final String RAG_CONTEXT_TEMPLATE = """

            EXISTING GRAMMAR POINTS (avoid duplicating these):
            %s
            """;

    private static final String FULL_PAGE_CONTEXT_TEMPLATE = """

            ─── FULL LESSON PAGE CONTEXT (adjacent sections on the same page) ───────────
            %s
            ─────────────────────────────────────────────────────────────────────────────
            Tip: Use this to find example sentences or explanations that clarify the
            grammar patterns in the CONTENT block above. Do NOT re-extract vocabulary
            from this context block — only grammar points.
            """;

    // ── Public builder API ────────────────────────────────────────────────────

    /**
     * Build the user prompt for a grammar extraction call.
     *
     * @param chunk           document chunk to extract from
     * @param jlptLevel       target JLPT level string (e.g. "N5")
     * @param ragSection      pre-formatted RAG context block (may be empty)
     * @param feedbackSection validation feedback for re-extraction (may be empty)
     * @return user prompt string ready to pass to the LLM
     */
    public static String buildExtractionPrompt(DocumentChunk chunk, String jlptLevel,
                                               String ragSection, String feedbackSection) {
        String safeContent = OcrTextPreprocessor.truncateToTokenBudget(
                chunk.getContent(), OcrTextPreprocessor.MAX_CONTENT_TOKENS);

        String sectionHint = buildSectionHint(chunk);
        String fullPageSection = buildFullPageContextSection(chunk);
        String contextBlocks = sectionHint + fullPageSection + ragSection + feedbackSection;

        return String.format(EXTRACTION_TEMPLATE,
                chunk.getPageStart(), chunk.getPageEnd(),
                jlptLevel,
                safeContent,
                contextBlocks,
                jlptLevel);
    }

    /**
     * Format a RAG context block for grammar.
     * Returns an empty string when no relevant context is available.
     */
    public static String formatRagContext(RagContext ragContext) {
        if (ragContext == null || !ragContext.hasContext()) return "";
        StringBuilder sb = new StringBuilder();
        for (RagContext.SimilarItem item : ragContext.getSimilarItems()) {
            if ("GRAMMAR".equals(item.getContentType())) {
                sb.append("- ").append(item.getTextContent()).append("\n");
            }
        }
        return sb.isEmpty() ? "" : String.format(RAG_CONTEXT_TEMPLATE, sb);
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    /**
     * Inject the full lesson page context when the chunk is a section sub-chunk.
     * Allows the grammar agent to see example sentences from adjacent sections.
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
     * Guides the LLM on what role this section plays in the lesson.
     */
    private static String buildSectionHint(DocumentChunk chunk) {
        String sectionType = chunk.getSectionType();
        if (sectionType == null || MnNSectionSplitter.UNKNOWN.equals(sectionType)) return "";
        return switch (sectionType) {
            case MnNSectionSplitter.GRAMMAR_PATTERN ->
                    "\n⚠ SECTION TYPE: SENTENCE PATTERNS (文型 / Mẫu câu)\n" +
                    "This section lists the core grammar patterns introduced in this lesson. " +
                    "Each numbered item (e.g. '1. ～は ～です') is a distinct grammar pattern. " +
                    "Extract ALL of them with full structure, meaning, and examples.\n";
            case MnNSectionSplitter.GRAMMAR_EXPLANATION ->
                    "\n⚠ SECTION TYPE: GRAMMAR EXPLANATION (文法 / Ngữ pháp)\n" +
                    "This section provides detailed notes explaining how each grammar pattern works, " +
                    "including usage rules, nuances, and common mistakes. " +
                    "Extract each distinct grammar point with its full explanation.\n";
            case MnNSectionSplitter.EXAMPLE ->
                    "\n⚠ SECTION TYPE: EXAMPLE SENTENCES (例文 / Ví dụ)\n" +
                    "This section demonstrates grammar patterns through example sentences. " +
                    "Identify which grammar pattern each example illustrates and include it " +
                    "as an exampleSentence for that grammar point.\n";
            default -> "";
        };
    }
}
