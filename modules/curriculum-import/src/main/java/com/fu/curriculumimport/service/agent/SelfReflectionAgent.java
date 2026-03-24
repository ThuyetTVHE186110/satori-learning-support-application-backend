package com.fu.curriculumimport.service.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fu.common.ai.PromptFragment;
import com.fu.curriculumimport.config.AgenticRagProperties;
import com.fu.curriculumimport.service.agent.ReflectionResult.ItemReflection;
import com.fu.curriculumimport.service.agent.ReflectionResult.ReflectionVerdict;
import com.fu.curriculumimport.service.ingestion.DocumentChunk;
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
 * Self-Reflection Agent — Implements Self-RAG (Asai et al., 2023) pattern.
 *
 * Unlike rule-based validation, this agent uses the LLM itself to critique
 * its own extraction output by evaluating three reflection tokens per item:
 *
 * 1. [IsRelevant] — Is the extracted item actually relevant to the source content?
 *    Catches items that were invented or pulled from model's training data.
 *
 * 2. [IsSupported] — Is the extraction factually supported by the source text?
 *    Catches hallucinated meanings, wrong readings, fabricated examples.
 *
 * 3. [IsUseful] — Is the extraction useful for language learning at this JLPT level?
 *    Catches items that are too advanced/simple or not pedagogically valuable.
 *
 * The agent receives both the source chunk and the extraction result,
 * then produces a structured critique with per-item assessments.
 *
 * This implements the "Critique" step in the Self-RAG framework where
 * the model generates reflection tokens to assess its own output quality.
 *
 * Reference: Self-RAG: Learning to Retrieve, Generate, and Critique (Asai et al., 2023)
 */
@Service
@Slf4j
public class SelfReflectionAgent {

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final AgenticRagProperties ragProperties;

    public SelfReflectionAgent(OpenAiChatModel chatModel, ObjectMapper objectMapper,
                                AgenticRagProperties ragProperties) {
        this.chatClient = ChatClient.builder(chatModel).build();
        this.objectMapper = objectMapper;
        this.ragProperties = ragProperties;
    }

    private static final String SYSTEM_PROMPT =
            "You are a quality assurance expert for Japanese language learning content extraction.\n" +
            "Your task is to evaluate whether the extracted content is:\n" +
            "1. RELEVANT to the source material (not invented or from external knowledge)\n" +
            "2. SUPPORTED by the source text (factually accurate extraction, not hallucinated)\n" +
            "3. USEFUL for language learning at the specified JLPT level\n\n" +
            "Evaluate FAIRLY. Items that clearly appear in the source text should score 0.8+.\n" +
            "Only flag items as unsupported when they are clearly hallucinated or fabricated.\n" +
            "Missing English translations or romaji are NOT grounds for rejection — only flag " +
            "items with WRONG Japanese text, WRONG readings, or FABRICATED meanings.\n" +
            "The source text may be a SECTION of a larger lesson — items extracted from " +
            "adjacent sections (vocabulary table, grammar explanation) are still RELEVANT.\n" +
            PromptFragment.JSON_ONLY;

    private static final String REFLECTION_PROMPT = """
            TASK: Critically evaluate the following AI extraction against the original source text.
            
            === ORIGINAL SOURCE TEXT (pages %d-%d) ===
            %s
            === END SOURCE TEXT ===
            
            === EXTRACTED CONTENT ===
            %s
            === END EXTRACTED CONTENT ===
            
            TARGET JLPT LEVEL: %s
            
            For EACH extracted item, evaluate these Self-RAG reflection tokens:
            - [IsRelevant]: Does this item come from the source text, not from external knowledge?
            - [IsSupported]: Are the meanings, readings, and explanations factually correct per the source?
            - [IsUseful]: Is this item appropriate for %s-level learners?
            
            Return JSON:
            {
              "overallVerdict": "APPROVED" | "MOSTLY_APPROVED" | "NEEDS_IMPROVEMENT" | "REJECTED",
              "overallScore": 0.0-1.0,
              "overallReasoning": "Brief explanation of overall quality",
              "recommendsReExtraction": true/false,
              "reExtractionSuggestion": "What to fix if re-extraction needed",
              "itemReflections": [
                {
                  "itemIdentifier": "日本語 or ～ている",
                  "contentType": "VOCABULARY" | "GRAMMAR",
                  "isRelevant": true/false,
                  "isSupported": true/false,
                  "isUseful": true/false,
                  "score": 0.0-1.0,
                  "reasoning": "Why this assessment"
                }
              ]
            }
            
            IMPORTANT GUIDELINES:
            - An item is RELEVANT if it appears in the source text OR is a standard vocabulary/grammar
              item for this lesson (the source may be a section of a larger lesson page).
            - An item is NOT SUPPORTED only if the meaning/reading is clearly WRONG or fabricated.
              Missing English translations, missing romaji, or incomplete explanations are NOT
              grounds for marking unsupported — only factual errors matter.
            - An item is USEFUL if it's appropriate for the target JLPT level.
            - Score: 1.0 = perfect, 0.8+ = good (minor issues only), 0.5-0.8 = needs improvement, <0.5 = reject
            - Default to 0.8 for items that are clearly present in the source with correct meanings.
            - Only score below 0.5 for items with WRONG Japanese text or FABRICATED meanings.
            """;

    /**
     * Perform self-reflection on an extraction result against its source chunk.
     *
     * @param chunk           the original source document chunk
     * @param extractionResult the AI extraction to critique
     * @param jlptLevel       target JLPT level for usefulness assessment
     * @return reflection result with per-item assessments and reflection tokens
     */
    public ReflectionResult reflect(DocumentChunk chunk, ExtractionResult extractionResult, String jlptLevel) {
        long startTime = System.currentTimeMillis();

        try {
            // Skip if no content to reflect on
            if (extractionResult.getVocabulary().isEmpty() && extractionResult.getGrammarPoints().isEmpty()) {
                log.debug("Skipping self-reflection for chunk {} — no extracted content", chunk.getChunkIndex());
                return ReflectionResult.passed();
            }

            // Prepare extracted content summary for the prompt
            String extractedSummary = buildExtractionSummary(extractionResult);

            // Use full page context (entire lesson) when available, so the LLM can see
            // vocabulary tables and grammar sections that feed the extraction.
            // Falls back to chunk content when no full page context is available.
            String rawSource = chunk.getFullPageContext() != null && !chunk.getFullPageContext().isBlank()
                    ? chunk.getFullPageContext()
                    : chunk.getContent();
            String sourceContent = truncateForReflection(rawSource);

            String userPrompt = String.format(REFLECTION_PROMPT,
                    chunk.getPageStart(), chunk.getPageEnd(),
                    sourceContent,
                    extractedSummary,
                    jlptLevel, jlptLevel);

            String response = chatClient.prompt()
                    .system(SYSTEM_PROMPT)
                    .user(userPrompt)
                    .options(OpenAiChatOptions.builder().maxTokens(8192).build())
                    .call()
                    .content();

            long processingTime = System.currentTimeMillis() - startTime;
            log.debug("Self-reflection for chunk {} completed in {}ms", chunk.getChunkIndex(), processingTime);

            return parseReflectionResponse(response);

        } catch (Exception e) {
            log.error("Self-reflection failed for chunk {}: {}", chunk.getChunkIndex(), e.getMessage());
            // Return a neutral result on failure — don't block the pipeline
            return buildFallbackResult("Self-reflection failed: " + e.getMessage());
        }
    }

    /**
     * Parse the LLM's JSON reflection response into a ReflectionResult.
     */
    private ReflectionResult parseReflectionResponse(String jsonResponse) {
        try {
            String cleaned = LlmResponseUtils.cleanJson(jsonResponse);
            Map<String, Object> parsed = objectMapper.readValue(cleaned, new TypeReference<>() {});

            // Parse verdict
            String verdictStr = (String) parsed.getOrDefault("overallVerdict", "MOSTLY_APPROVED");
            ReflectionVerdict verdict = parseVerdict(verdictStr);

            double overallScore = ((Number) parsed.getOrDefault("overallScore", 0.7)).doubleValue();
            String reasoning = (String) parsed.getOrDefault("overallReasoning", "");
            // Use convertValue instead of a direct cast — the LLM may return the JSON boolean
            // as a String ("true"/"false") or Integer (0/1), causing ClassCastException.
            boolean recommends = objectMapper.convertValue(
                    parsed.getOrDefault("recommendsReExtraction", false), Boolean.class);
            String suggestion = (String) parsed.getOrDefault("reExtractionSuggestion", "");

            // Parse item reflections
            List<ItemReflection> items = parseItemReflections(parsed);

            return ReflectionResult.builder()
                    .reflectionVerdict(verdict)
                    .reflectionScore(overallScore)
                    .reflectionReasoning(reasoning)
                    .itemReflections(items)
                    .llmRecommendsReExtraction(recommends)
                    .llmReExtractionSuggestion(suggestion)
                    // Grounding fields will be filled by GroundingVerifier
                    .groundingScore(0.0)
                    .groundedCount(0)
                    .hallucinatedCount(0)
                    .groundingChecks(List.of())
                    .hallucinatedItems(List.of())
                    .combinedScore(overallScore) // will be recalculated later
                    .combinedVerdict(ReflectionResult.CombinedVerdict.GOOD)
                    .needsReExtraction(recommends)
                    .build();

        } catch (JsonProcessingException e) {
            log.error("Failed to parse self-reflection response: {}", e.getMessage());
            return buildFallbackResult("Parse error: " + e.getMessage());
        }
    }

    /**
     * Max items included in the reflection summary per content type.
     * Prevents runaway prompt size (and response size) when a lesson has 50+ items.
     * Items beyond this limit are listed as a count only; the LLM still sees a representative sample.
     */
    private static final int MAX_REFLECTION_ITEMS = 30;

    /**
     * Build a concise summary of extracted content for the reflection prompt.
     * Capped at {@value #MAX_REFLECTION_ITEMS} items per type to bound response size.
     */
    private String buildExtractionSummary(ExtractionResult result) {
        StringBuilder sb = new StringBuilder();

        List<ExtractionResult.ExtractedVocabulary> vocabList = result.getVocabulary();
        if (!vocabList.isEmpty()) {
            sb.append("VOCABULARY ITEMS:\n");
            int vocabLimit = Math.min(vocabList.size(), MAX_REFLECTION_ITEMS);
            for (int i = 0; i < vocabLimit; i++) {
                ExtractionResult.ExtractedVocabulary vocab = vocabList.get(i);
                sb.append(String.format("- %s (%s) [%s]: %s / %s [%s]\n",
                        vocab.getJapaneseText() != null ? vocab.getJapaneseText() : "?",
                        vocab.getReading()      != null ? vocab.getReading()      : "?",
                        vocab.getRomaji()       != null ? vocab.getRomaji()       : "?",
                        vocab.getMeaningVi()    != null ? vocab.getMeaningVi()    : "?",
                        vocab.getMeaningEn()    != null ? vocab.getMeaningEn()    : "?",
                        vocab.getPartOfSpeech() != null ? vocab.getPartOfSpeech(): "?"));
            }
            if (vocabList.size() > MAX_REFLECTION_ITEMS) {
                sb.append(String.format("... and %d more vocabulary items (not shown)\n",
                        vocabList.size() - MAX_REFLECTION_ITEMS));
            }
        }

        List<ExtractionResult.ExtractedGrammar> grammarList = result.getGrammarPoints();
        if (!grammarList.isEmpty()) {
            sb.append("GRAMMAR POINTS:\n");
            int grammarLimit = Math.min(grammarList.size(), MAX_REFLECTION_ITEMS);
            for (int i = 0; i < grammarLimit; i++) {
                ExtractionResult.ExtractedGrammar grammar = grammarList.get(i);
                sb.append(String.format("- %s: %s / %s — %s\n",
                        grammar.getPattern()            != null ? grammar.getPattern()            : "?",
                        grammar.getMeaningVi()          != null ? grammar.getMeaningVi()          : "?",
                        grammar.getMeaningEn()          != null ? grammar.getMeaningEn()          : "?",
                        grammar.getShortExplanationVi() != null ? grammar.getShortExplanationVi() : "?"));
            }
            if (grammarList.size() > MAX_REFLECTION_ITEMS) {
                sb.append(String.format("... and %d more grammar points (not shown)\n",
                        grammarList.size() - MAX_REFLECTION_ITEMS));
            }
        }

        return sb.toString();
    }

    /**
     * Parse item-level reflections from the LLM response.
     */
    @SuppressWarnings("unchecked")
    private List<ItemReflection> parseItemReflections(Map<String, Object> parsed) {
        List<ItemReflection> items = new ArrayList<>();

        try {
            Object rawItems = parsed.get("itemReflections");
            if (rawItems == null) return items;

            String json = objectMapper.writeValueAsString(rawItems);
            List<Map<String, Object>> itemMaps = objectMapper.readValue(json, new TypeReference<>() {});

            for (Map<String, Object> item : itemMaps) {
                items.add(ItemReflection.builder()
                        .itemIdentifier((String) item.getOrDefault("itemIdentifier", "unknown"))
                        .contentType((String) item.getOrDefault("contentType", "VOCABULARY"))
                        .isRelevant(objectMapper.convertValue(item.getOrDefault("isRelevant", true), Boolean.class))
                        .isSupported(objectMapper.convertValue(item.getOrDefault("isSupported", true), Boolean.class))
                        .isUseful(objectMapper.convertValue(item.getOrDefault("isUseful", true), Boolean.class))
                        .score(((Number) item.getOrDefault("score", 0.7)).doubleValue())
                        .reasoning((String) item.getOrDefault("reasoning", ""))
                        .build());
            }
        } catch (Exception e) {
            log.warn("Failed to parse item reflections: {}", e.getMessage());
        }

        return items;
    }

    private ReflectionVerdict parseVerdict(String verdict) {
        try {
            return ReflectionVerdict.valueOf(verdict);
        } catch (IllegalArgumentException e) {
            return ReflectionVerdict.MOSTLY_APPROVED;
        }
    }

    /**
     * Truncate source content to fit in reflection prompt context window.
     * Keep first 4000 chars to stay within token limits.
     */
    private String truncateForReflection(String content) {
        int maxChars = ragProperties.getSelfReflectionMaxSourceChars();
        if (content.length() <= maxChars) return content;
        return content.substring(0, maxChars) + "\n... [truncated for reflection]";
    }

    private ReflectionResult buildFallbackResult(String reason) {
        return ReflectionResult.builder()
                .reflectionVerdict(ReflectionVerdict.NEEDS_IMPROVEMENT)
                .reflectionScore(0.5)
                .reflectionReasoning("Fallback assessment (reflection unavailable): " + reason)
                .itemReflections(Collections.emptyList())
                .llmRecommendsReExtraction(false)
                .groundingScore(0.0)
                .groundedCount(0)
                .hallucinatedCount(0)
                .groundingChecks(List.of())
                .hallucinatedItems(List.of())
                .combinedScore(0.5)
                .combinedVerdict(ReflectionResult.CombinedVerdict.MARGINAL)
                .needsReExtraction(false)
                .build();
    }
}
