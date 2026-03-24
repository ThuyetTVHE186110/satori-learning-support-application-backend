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

            String response = chatClient.prompt()
                    .system(VocabularyPromptBuilder.SYSTEM_PROMPT)
                    .user(prompt)
                    .options(OpenAiChatOptions.builder().maxTokens(16384).build())
                    .call()
                    .content();

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

            String response = chatClient.prompt()
                    .system(VocabularyPromptBuilder.SYSTEM_PROMPT
                            + VocabularyPromptBuilder.REEXTRACTION_SYSTEM_SUFFIX)
                    .user(prompt)
                    .options(OpenAiChatOptions.builder().maxTokens(16384).build())
                    .call()
                    .content();

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
