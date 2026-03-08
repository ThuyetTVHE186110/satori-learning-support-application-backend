package com.fu.curriculumimport.service.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fu.curriculumimport.service.embedding.RagContext;
import com.fu.curriculumimport.service.ingestion.DocumentChunk;
import com.fu.curriculumimport.util.LlmResponseUtils;
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

            • If a meaning IS present in the source text → extract it exactly.
              Set "meaningSource": "SOURCE".

            • If a meaning is NOT present →  infer the most accurate meaning using:
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

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Extract vocabulary from a chunk, with optional RAG context.
     */
    public ExtractionResult extractVocabulary(DocumentChunk chunk, String jlptLevel,
                                               RagContext ragContext) {
        long t0 = System.currentTimeMillis();
        try {
            String ragSection = formatRagContext(ragContext);
            String prompt = buildPrompt(chunk, jlptLevel, ragSection, "");

            String response = chatClient.prompt()
                    .system(SYSTEM_PROMPT)
                    .user(prompt)
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

        return String.format(EXTRACTION_PROMPT,
                chunk.getPageStart(), chunk.getPageEnd(),
                jlptLevel,
                lessonTopic,
                sourceType,
                ragSection + feedbackSection,
                chunk.getContent(),
                buildMeaningHint(chunk),
                jlptLevel);
    }

    /**
     * Attempt to extract a lesson topic from the chunk header (PPTX slides
     * are formatted as "[SLIDE N] タイトル: <title>").
     */
    private String inferLessonTopic(DocumentChunk chunk) {
        String content = chunk.getContent();
        if (content == null) return "unknown";
        // PPTX structured format: first line contains the title
        String firstLine = content.lines().findFirst().orElse("").trim();
        if (firstLine.contains("タイトル:")) {
            return firstLine.substring(firstLine.indexOf("タイトル:") + 5).trim();
        }
        if (firstLine.contains("[SLIDE")) {
            return firstLine;
        }
        // Fallback: first 120 chars
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
