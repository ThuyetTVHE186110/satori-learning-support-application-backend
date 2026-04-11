package com.fu.curriculumimport.service.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fu.curriculumimport.service.agent.prompt.VocabularyPromptBuilder;
import com.fu.curriculumimport.service.embedding.RagContext;
import com.fu.curriculumimport.service.ingestion.DocumentChunk;
import com.fu.curriculumimport.service.ingestion.MarkdownVocabParser;
import com.fu.curriculumimport.service.ingestion.MnNTableParser;
import com.fu.curriculumimport.service.vision.VisionPageExtractor;
import com.fu.curriculumimport.util.LlmResponseUtils;
import com.fu.curriculumimport.util.LlmStreamCollector;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 * <p>Prompt strings live in {@link VocabularyPromptBuilder} — this class handles
 * only LLM-call mechanics, fast-path logic, and response parsing.
 *
 * <p>Reference: Agentic RAG specialized worker pattern (Asai et al. 2023 Self-RAG)
 */
@Service
@Slf4j
public class VocabularyExtractionAgent {

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final VisionPageExtractor visionPageExtractor;
    private final MnNTableParser mnNTableParser;
    private final MarkdownVocabParser markdownVocabParser;

    public VocabularyExtractionAgent(OpenAiChatModel chatModel, ObjectMapper objectMapper,
                                      VisionPageExtractor visionPageExtractor,
                                      MnNTableParser mnNTableParser,
                                      MarkdownVocabParser markdownVocabParser) {
        this.chatClient = ChatClient.builder(chatModel).build();
        this.objectMapper = objectMapper;
        this.visionPageExtractor = visionPageExtractor;
        this.mnNTableParser = mnNTableParser;
        this.markdownVocabParser = markdownVocabParser;
    }

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

        // ── Fast path 1: structured table rows from OCR ────────────────────────
        if (chunk.getStructuredTableRows() != null && !chunk.getStructuredTableRows().isEmpty()) {
            List<ExtractionResult.ExtractedVocabulary> tableVocab =
                    mnNTableParser.parse(chunk.getStructuredTableRows(), jlptLevel);
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
            log.debug("Chunk {}: table parse failed (low rate), trying markdown tables", chunk.getChunkIndex());
        }

        // ── Fast path 1.5: markdown tables from Document AI / Gemini ─────────
        // When content contains pipe-delimited markdown tables, parse standard
        // vocab tables directly (0 LLM calls). Complex tables (country/grid)
        // return empty → fall through to vision or LLM path.
        if (chunk.getContent() != null && chunk.getContent().contains("|")) {
            List<ExtractionResult.ExtractedVocabulary> mdVocab =
                    markdownVocabParser.parse(chunk.getContent(), jlptLevel);
            if (!mdVocab.isEmpty()) {
                log.info("Chunk {}: markdown table parser extracted {} vocab items (no LLM call)",
                        chunk.getChunkIndex(), mdVocab.size());
                return ExtractionResult.builder()
                        .chunkIndex(chunk.getChunkIndex())
                        .confidence(0.90)
                        .tokensUsed(0)
                        .processingTimeMs(System.currentTimeMillis() - t0)
                        .lessons(Collections.emptyList())
                        .vocabulary(mdVocab)
                        .grammarPoints(Collections.emptyList())
                        .build();
            }
        }

        // ── Fast path 2: vision-first extraction (Tier 2) ─────────────────────
        // Available when ScannedPdfIngestionService propagated page images to this chunk.
        // Skip vision when structured data exists (table rows or markdown tables) — the
        // text LLM path is cheaper and sufficient when Document AI provided good text.
        boolean hasStructuredData = (chunk.getStructuredTableRows() != null && !chunk.getStructuredTableRows().isEmpty())
                || (chunk.getContent() != null && chunk.getContent().contains("|"));
        if (!hasStructuredData && chunk.getPageImages() != null && !chunk.getPageImages().isEmpty()) {
            String lessonTopic = chunk.getLessonTitle() != null ? chunk.getLessonTitle()
                    : chunk.getContent().substring(0, Math.min(80, chunk.getContent().length()));
            ExtractionResult visionResult = visionPageExtractor.extractVocabulary(
                    chunk.getPageImages(), jlptLevel, lessonTopic, chunk.getChunkIndex());
            boolean visionAccepted = visionResult.getConfidence() >= VisionPageExtractor.VISION_CONFIDENCE_THRESHOLD
                    && !visionResult.getVocabulary().isEmpty();
            if (visionAccepted) {
                log.info("Chunk {}: vision extracted {} vocab items (confidence={})",
                        chunk.getChunkIndex(), visionResult.getVocabulary().size(),
                        visionResult.getConfidence());
                return visionResult;
            }
            if (visionResult.getVocabulary().isEmpty()) {
                log.info("Chunk {}: vision returned 0 vocab items (confidence={}) — falling back to text LLM",
                        chunk.getChunkIndex(), visionResult.getConfidence());
            } else {
                log.info("Chunk {}: vision confidence {} below threshold {}, falling back to text LLM",
                        chunk.getChunkIndex(), visionResult.getConfidence(),
                        VisionPageExtractor.VISION_CONFIDENCE_THRESHOLD);
            }
        }

        // ── LLM path (text-based fallback) ────────────────────────────────────

        try {
            String ragSection = VocabularyPromptBuilder.formatRagContext(ragContext);
            String prompt = VocabularyPromptBuilder.buildExtractionPrompt(chunk, jlptLevel, ragSection, "");
            int maxTokens = estimateMaxTokens(chunk);

            String response = LlmStreamCollector.collect(
                    chatClient.prompt()
                            .system(VocabularyPromptBuilder.SYSTEM_PROMPT)
                            .user(prompt)
                            .options(OpenAiChatOptions.builder()
                                    .temperature(0.0).seed(42)
                                    .maxTokens(maxTokens).build())
                            .stream()
                            .content());

            return parseResponse(response, chunk.getChunkIndex(), System.currentTimeMillis() - t0);
        } catch (Exception e) {
            log.error("Vocabulary extraction failed for chunk {}: {}", chunk.getChunkIndex(), e.getMessage());
            return ExtractionResult.empty(chunk.getChunkIndex(), System.currentTimeMillis() - t0);
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
            String ragSection = VocabularyPromptBuilder.formatRagContext(ragContext);
            String feedbackSection = "\n\nPREVIOUS EXTRACTION ISSUES — please fix ALL of these:\n"
                    + validationFeedback;

            String prompt = VocabularyPromptBuilder.buildExtractionPrompt(
                    chunk, jlptLevel, ragSection, feedbackSection);
            int maxTokens = estimateMaxTokens(chunk);

            String response = LlmStreamCollector.collect(
                    chatClient.prompt()
                            .system(VocabularyPromptBuilder.SYSTEM_PROMPT
                                    + VocabularyPromptBuilder.REEXTRACTION_SYSTEM_SUFFIX)
                            .user(prompt)
                            .options(OpenAiChatOptions.builder()
                                    .temperature(0.0).seed(42)
                                    .maxTokens(maxTokens).build())
                            .stream()
                            .content());

            return parseResponse(response, chunk.getChunkIndex(), System.currentTimeMillis() - t0);
        } catch (Exception e) {
            log.error("Vocabulary re-extraction failed for chunk {}: {}", chunk.getChunkIndex(), e.getMessage());
            return ExtractionResult.empty(chunk.getChunkIndex(), System.currentTimeMillis() - t0);
        }
    }

    // ── Response parsing ─────────────────────────────────────────────────────

    private ExtractionResult parseResponse(String json, int chunkIndex, long ms) {
        try {
            Map<String, Object> parsed = objectMapper.readValue(LlmResponseUtils.cleanJson(json), new TypeReference<>() {});
            double confidence = ((Number) parsed.getOrDefault("confidence", 0.5)).doubleValue();

            List<ExtractionResult.ExtractedVocabulary> vocab = parseField(
                    parsed, "vocabulary", new TypeReference<>() {});

            if (vocab != null) {
                vocab.forEach(v -> {
                    // MnN Vietnamese edition always has meanings in source;
                    // fall back to SOURCE (not AI_INFERRED) when the LLM omits the field.
                    if (v.getMeaningSource() == null || v.getMeaningSource().isBlank()) {
                        v.setMeaningSource("SOURCE");
                    }
                    if (v.getUsageNote() == null) v.setUsageNote("");
                    if (v.getSuffixNote() == null) v.setSuffixNote("");
                    // Post-process: fix furigana-instead-of-kanji issues from LLM output
                    fixFuriganaKanji(v);
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
            return ExtractionResult.empty(chunkIndex, ms);
        }
    }

    // ── Dynamic token budget ────────────────────────────────────────────────

    private static final int MIN_MAX_TOKENS = 4096;
    private static final int MAX_MAX_TOKENS = 16384;
    private static final int TOKENS_PER_VOCAB_ITEM = 200;
    private static final int JSON_OVERHEAD_TOKENS = 512;

    /**
     * Estimate the maxTokens budget from chunk content size.
     *
     * <p>Each non-empty line in the source is a potential vocabulary entry;
     * each entry produces ~200 output tokens in JSON.  Small single-lesson
     * chunks (~10 items) get 4 096 tokens and finish fast.  Full textbook
     * chapters with 60+ items scale up to 16 384.
     */
    static int estimateMaxTokens(DocumentChunk chunk) {
        String content = chunk.getContent();
        if (content == null || content.isBlank()) return MIN_MAX_TOKENS;

        long lineCount = content.lines()
                .filter(l -> !l.isBlank())
                .count();

        // When fullPageContext is available (multi-section chunk), use the
        // larger of the two to avoid underestimating.
        String fullPage = chunk.getFullPageContext();
        if (fullPage != null && fullPage.length() > content.length()) {
            lineCount = Math.max(lineCount, fullPage.lines()
                    .filter(l -> !l.isBlank())
                    .count());
        }

        int estimated = (int) (lineCount * TOKENS_PER_VOCAB_ITEM) + JSON_OVERHEAD_TOKENS;
        int clamped = Math.min(MAX_MAX_TOKENS, Math.max(MIN_MAX_TOKENS, estimated));

        log.debug("estimateMaxTokens: {} non-empty lines → {} estimated → {} clamped",
                lineCount, estimated, clamped);
        return clamped;
    }

    // ── Post-processing: furigana/kanji fix ──────────────────────────────────

    /** Pure hiragana (may include prolonged sound mark ー). */
    private static final Pattern PURE_HIRAGANA = Pattern.compile(
            "^[\\u3040-\\u309Fー]+$");

    /** Pure kana — hiragana or katakana. */
    private static final Pattern PURE_KANA = Pattern.compile(
            "^[\\u3040-\\u30FFー]+$");

    /** CJK ideographs (kanji). */
    private static final Pattern HAS_KANJI = Pattern.compile("[\\u4E00-\\u9FFF]");

    /**
     * Glued "kana+kanji" pattern: 2+ kana chars followed by kanji portion.
     * E.g. "かんこく韓国", "えいご英語", "アメリカじん人".
     */
    private static final Pattern KANA_GLUED_KANJI = Pattern.compile(
            "^([\\u3040-\\u30FFー]{2,})([\\u4E00-\\u9FFF][\\u4E00-\\u9FFF\\u3040-\\u30FFー]*)$");

    /**
     * Fix common LLM mistakes with furigana/kanji in vocabulary items.
     *
     * <p>Cases handled:
     * <ol>
     *   <li><b>japaneseText is pure kana but kanjiWriting has kanji</b> —
     *       swap: japaneseText=kanjiWriting, reading=old japaneseText</li>
     *   <li><b>japaneseText contains glued furigana+kanji</b> (e.g. "かんこく韓国") —
     *       split: japaneseText=kanji, reading=kana</li>
     *   <li><b>japaneseText is pure kana, reading is same, kanjiWriting empty</b> —
     *       no fix possible (genuinely kana-only word like わたし)</li>
     * </ol>
     */
    static void fixFuriganaKanji(ExtractionResult.ExtractedVocabulary v) {
        String jp = v.getJapaneseText();
        if (jp == null || jp.isBlank()) return;

        String kanji = v.getKanjiWriting();
        String reading = v.getReading();

        // Case 1: japaneseText is pure kana but kanjiWriting has actual kanji
        // e.g. japaneseText="かんこく", kanjiWriting="韓国" → swap
        if (PURE_KANA.matcher(jp).matches()
                && kanji != null && !kanji.isBlank() && HAS_KANJI.matcher(kanji).find()) {
            if (reading == null || reading.isBlank() || reading.equals(kanji)) {
                v.setReading(jp);
            }
            v.setJapaneseText(kanji);
            log.debug("fixFuriganaKanji: swapped japaneseText='{}' ← kanjiWriting='{}'", kanji, jp);
            return;
        }

        // Case 2: japaneseText contains glued furigana+kanji (e.g. "かんこく韓国")
        Matcher gluedMatch = KANA_GLUED_KANJI.matcher(jp);
        if (gluedMatch.matches()) {
            String kanaPart = gluedMatch.group(1);   // "かんこく" or "アメリカじん"
            String kanjiPart = gluedMatch.group(2);   // "韓国" or "人"

            // Extract leading katakana prefix shared between reading and word
            int kataEnd = 0;
            for (int i = 0; i < kanaPart.length(); ) {
                int cp = kanaPart.codePointAt(i);
                if ((cp >= 0x30A0 && cp <= 0x30FF) || cp == 'ー') {
                    kataEnd = i + Character.charCount(cp);
                    i = kataEnd;
                } else {
                    break;
                }
            }
            String kataPrefix = kanaPart.substring(0, kataEnd);
            String fixedWord = kataPrefix + kanjiPart;

            if (reading == null || reading.isBlank() || reading.equals(jp)) {
                v.setReading(kanaPart);
            }
            v.setJapaneseText(fixedWord);
            if (HAS_KANJI.matcher(fixedWord).find()) {
                v.setKanjiWriting(fixedWord);
            }
            log.debug("fixFuriganaKanji: split glued '{}' → japaneseText='{}', reading='{}'",
                    jp, fixedWord, kanaPart);
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private <T> T parseField(Map<String, Object> parsed, String field, TypeReference<T> type) {
        try {
            if (!parsed.containsKey(field)) return null;
            return objectMapper.readValue(objectMapper.writeValueAsString(parsed.get(field)), type);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

}
