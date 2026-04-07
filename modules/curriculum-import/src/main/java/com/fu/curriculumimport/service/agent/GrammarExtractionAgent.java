package com.fu.curriculumimport.service.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fu.curriculumimport.service.agent.prompt.GrammarPromptBuilder;
import com.fu.curriculumimport.service.agent.prompt.MarkdownGrammarSplitter;
import com.fu.curriculumimport.service.agent.prompt.MarkdownGrammarSplitter.GrammarSection;
import com.fu.curriculumimport.service.embedding.RagContext;
import com.fu.curriculumimport.service.ingestion.DocumentChunk;
import com.fu.curriculumimport.service.vision.VisionPageExtractor;
import com.fu.curriculumimport.util.LlmResponseUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Specialized Grammar Extraction Agent.
 *
 * <p>Extraction strategy (in priority order):
 * <ol>
 *   <li><b>Per-section extraction</b> (preferred): When chunk content is markdown with
 *       numbered headings (## 1., ## 2., ...), each heading is extracted individually
 *       using GPT-4o — making it structurally impossible to split one section into
 *       multiple grammar points.</li>
 *   <li><b>Vision path</b> (Tier 2): When page images are available and markdown
 *       splitting is not applicable.</li>
 *   <li><b>Bulk text extraction</b> (fallback): Sends the entire chunk content to
 *       GPT-4o-mini when neither per-section nor vision is available.</li>
 * </ol>
 *
 * <p>Prompt strings live in {@link GrammarPromptBuilder} — this class handles
 * only LLM-call mechanics and response parsing.
 */
@Service
@Slf4j
public class GrammarExtractionAgent {

    /** Model used for per-section extraction. */
    private static final String PER_SECTION_MODEL = "gpt-4o";

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final VisionPageExtractor visionPageExtractor;

    public GrammarExtractionAgent(OpenAiChatModel chatModel, ObjectMapper objectMapper,
                                   VisionPageExtractor visionPageExtractor) {
        this.chatClient = ChatClient.builder(chatModel).build();
        this.objectMapper = objectMapper;
        this.visionPageExtractor = visionPageExtractor;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Extract grammar points from a chunk with optional RAG context.
     */
    public ExtractionResult extractGrammar(DocumentChunk chunk, String jlptLevel, RagContext ragContext) {
        long startTime = System.currentTimeMillis();

        // ── Per-section extraction (preferred for markdown content) ───────────
        // When Layout Parser produces structured markdown with numbered headings,
        // split by heading and extract each section individually with a smarter model.
        List<GrammarSection> sections = MarkdownGrammarSplitter.split(chunk.getContent());
        if (sections.size() >= 2) {
            log.info("Chunk {}: markdown has {} numbered sections — using per-section extraction ({})",
                    chunk.getChunkIndex(), sections.size(), PER_SECTION_MODEL);
            return extractPerSection(sections, chunk, jlptLevel, startTime);
        }

        // ── Vision-first path (Tier 2) ─────────────────────────────────────────
        // Skip vision when markdown structure is available — text LLM path handles it well
        // and avoids redundant Gemini API calls when Document AI already provides structure.
        boolean hasMarkdownStructure = chunk.getContent() != null
                && (chunk.getContent().contains("##") || chunk.getContent().contains("|"));
        if (!hasMarkdownStructure && chunk.getPageImages() != null && !chunk.getPageImages().isEmpty()) {
            String lessonTopic = chunk.getLessonTitle() != null ? chunk.getLessonTitle()
                    : chunk.getContent().substring(0, Math.min(80, chunk.getContent().length()));
            ExtractionResult visionResult = visionPageExtractor.extractGrammar(
                    chunk.getPageImages(), jlptLevel, lessonTopic, chunk.getChunkIndex());
            boolean visionAccepted = visionResult.getConfidence() >= VisionPageExtractor.VISION_CONFIDENCE_THRESHOLD
                    && !visionResult.getGrammarPoints().isEmpty();
            if (visionAccepted) {
                log.info("Chunk {}: vision extracted {} grammar items (confidence={})",
                        chunk.getChunkIndex(), visionResult.getGrammarPoints().size(),
                        visionResult.getConfidence());
                return visionResult;
            }
            if (visionResult.getGrammarPoints().isEmpty()) {
                log.info("Chunk {}: vision returned 0 grammar items — falling back to text LLM",
                        chunk.getChunkIndex());
            } else {
                log.info("Chunk {}: vision confidence {} below threshold {}, falling back to text LLM",
                        chunk.getChunkIndex(), visionResult.getConfidence(),
                        VisionPageExtractor.VISION_CONFIDENCE_THRESHOLD);
            }
        }

        // ── Bulk text-based LLM path (fallback) ─────────────────────────────────
        return extractBulk(chunk, jlptLevel, ragContext, "", startTime);
    }

    /**
     * Re-extract with validation feedback (Corrective RAG).
     */
    public ExtractionResult reExtractWithFeedback(DocumentChunk chunk, String jlptLevel,
                                                    RagContext ragContext, String validationFeedback) {
        long startTime = System.currentTimeMillis();

        // On re-extraction, try per-section again if markdown structure is available
        List<GrammarSection> sections = MarkdownGrammarSplitter.split(chunk.getContent());
        if (sections.size() >= 2) {
            log.info("Chunk {}: re-extraction using per-section ({} sections, {})",
                    chunk.getChunkIndex(), sections.size(), PER_SECTION_MODEL);
            return extractPerSection(sections, chunk, jlptLevel, startTime);
        }

        return extractBulk(chunk, jlptLevel, ragContext, validationFeedback, startTime);
    }

    // ── Per-section extraction ────────────────────────────────────────────────

    /**
     * Extract each numbered markdown section individually with GPT-4o.
     *
     * <p>One LLM call per section → structurally enforces one grammar point per heading.
     * Uses a smarter model (GPT-4o) for better verbatim fidelity.
     */
    private ExtractionResult extractPerSection(List<GrammarSection> sections,
                                                DocumentChunk chunk, String jlptLevel,
                                                long startTime) {
        List<ExtractionResult.ExtractedGrammar> allGrammar = new ArrayList<>();
        int totalTokens = 0;
        // Track already-extracted patterns so subsequent sections can avoid duplicates
        List<String> extractedPatterns = new ArrayList<>();

        for (GrammarSection section : sections) {
            try {
                String alreadyExtracted = extractedPatterns.isEmpty() ? ""
                        : "\n\n⚠ CÁC MẪU ĐÃ TRÍCH XUẤT (KHÔNG trích lại):\n- "
                        + String.join("\n- ", extractedPatterns) + "\n";

                String userPrompt = GrammarPromptBuilder.buildSingleSectionPrompt(
                        section.fullSection(), section.stt(),
                        chunk.getLessonTitle(), jlptLevel,
                        chunk.getFullPageContext(),
                        alreadyExtracted);

                String response = chatClient.prompt()
                        .system(GrammarPromptBuilder.SINGLE_SECTION_SYSTEM_PROMPT)
                        .user(userPrompt)
                        .options(OpenAiChatOptions.builder()
                                .model(PER_SECTION_MODEL)
                                .maxTokens(4096)
                                .build())
                        .call()
                        .content();

                ExtractionResult sectionResult = parseResponse(
                        response, chunk.getChunkIndex(), 0);
                allGrammar.addAll(sectionResult.getGrammarPoints());
                totalTokens += sectionResult.getTokensUsed();
                // Accumulate extracted patterns for dedup in subsequent sections
                for (ExtractionResult.ExtractedGrammar gp : sectionResult.getGrammarPoints()) {
                    if (gp.getPattern() != null) extractedPatterns.add(gp.getPattern());
                }

                log.debug("Chunk {} section {}/{} '{}': extracted {} grammar point(s)",
                        chunk.getChunkIndex(), section.stt(), sections.size(),
                        section.headingTitle(),
                        sectionResult.getGrammarPoints().size());

            } catch (Exception e) {
                log.warn("Chunk {} section {} extraction failed: {}",
                        chunk.getChunkIndex(), section.stt(), e.getMessage());
            }
        }

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("Chunk {}: per-section extraction complete — {} sections → {} grammar points ({} ms)",
                chunk.getChunkIndex(), sections.size(), allGrammar.size(), elapsed);

        return ExtractionResult.builder()
                .chunkIndex(chunk.getChunkIndex())
                .confidence(allGrammar.isEmpty() ? 0.0 : 0.92)
                .tokensUsed(totalTokens)
                .processingTimeMs(elapsed)
                .lessons(Collections.emptyList())
                .vocabulary(Collections.emptyList())
                .grammarPoints(allGrammar)
                .build();
    }

    // ── Bulk text extraction ─────────────────────────────────────────────────

    private ExtractionResult extractBulk(DocumentChunk chunk, String jlptLevel,
                                          RagContext ragContext, String validationFeedback,
                                          long startTime) {
        try {
            String ragSection = GrammarPromptBuilder.formatRagContext(ragContext);
            String feedbackSection = validationFeedback.isEmpty() ? ""
                    : "\n\nPREVIOUS EXTRACTION ISSUES (please fix):\n" + validationFeedback;
            String userPrompt = GrammarPromptBuilder.buildExtractionPrompt(
                    chunk, jlptLevel, ragSection, feedbackSection);

            String systemPrompt = validationFeedback.isEmpty()
                    ? GrammarPromptBuilder.SYSTEM_PROMPT
                    : GrammarPromptBuilder.SYSTEM_PROMPT + GrammarPromptBuilder.REEXTRACTION_SYSTEM_SUFFIX;

            String response = chatClient.prompt()
                    .system(systemPrompt)
                    .user(userPrompt)
                    .options(OpenAiChatOptions.builder().maxTokens(8192).build())
                    .call()
                    .content();

            return parseResponse(response, chunk.getChunkIndex(), System.currentTimeMillis() - startTime);

        } catch (Exception e) {
            log.error("Grammar extraction failed for chunk {}: {}", chunk.getChunkIndex(), e.getMessage());
            return ExtractionResult.empty(chunk.getChunkIndex(), System.currentTimeMillis() - startTime);
        }
    }

    // ── Response parsing ──────────────────────────────────────────────────────

    private ExtractionResult parseResponse(String jsonResponse, int chunkIndex, long processingTimeMs) {
        try {
            String cleaned = LlmResponseUtils.cleanJson(jsonResponse);
            Map<String, Object> parsed = objectMapper.readValue(cleaned, new TypeReference<>() {});

            double confidence = ((Number) parsed.getOrDefault("confidence", 0.5)).doubleValue();

            List<ExtractionResult.ExtractedGrammar> grammarPoints = parseField(parsed, "grammarPoints",
                    new TypeReference<>() {});

            return ExtractionResult.builder()
                    .chunkIndex(chunkIndex)
                    .confidence(confidence)
                    .tokensUsed(LlmResponseUtils.estimateTokens(jsonResponse))
                    .processingTimeMs(processingTimeMs)
                    .lessons(Collections.emptyList())
                    .vocabulary(Collections.emptyList())
                    .grammarPoints(grammarPoints != null ? grammarPoints : Collections.emptyList())
                    .build();

        } catch (JsonProcessingException e) {
            log.error("Failed to parse grammar extraction response for chunk {}: {}", chunkIndex, e.getMessage());
            return ExtractionResult.empty(chunkIndex, processingTimeMs);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private <T> T parseField(Map<String, Object> parsed, String field, TypeReference<T> type) {
        try {
            if (!parsed.containsKey(field)) return null;
            String json = objectMapper.writeValueAsString(parsed.get(field));
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
