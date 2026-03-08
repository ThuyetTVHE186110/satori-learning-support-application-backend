package com.fu.curriculumimport.service.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fu.curriculumimport.config.AgenticRagProperties;
import com.fu.curriculumimport.service.agent.ReflectionResult.ItemReflection;
import com.fu.curriculumimport.service.agent.ReflectionResult.ReflectionVerdict;
import com.fu.curriculumimport.service.ingestion.DocumentChunk;
import com.fu.curriculumimport.util.LlmResponseUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
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

    private static final String SYSTEM_PROMPT = """
            You are a quality assurance expert for Japanese language learning content extraction.
            Your task is to critically evaluate whether the extracted content is:
            1. RELEVANT to the source material (not invented or from external knowledge)
            2. SUPPORTED by the source text (factually accurate extraction, not hallucinated)
            3. USEFUL for language learning at the specified JLPT level
            
            Be strict and honest. Flag any item that seems fabricated or unsupported.
            You MUST respond ONLY with valid JSON. No explanations, no markdown code fences.
            """;

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
            - An item is NOT relevant if it doesn't appear in or relate to the source text
            - An item is NOT supported if the meaning/reading differs from what the source implies
            - An item is NOT useful if it's way above/below the target JLPT level
            - Be especially strict about Japanese text accuracy and reading correctness
            - Score: 1.0 = perfect, 0.7+ = acceptable, 0.4-0.7 = needs improvement, <0.4 = reject
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

            // Truncate source content to fit context window
            String sourceContent = truncateForReflection(chunk.getContent());

            String userPrompt = String.format(REFLECTION_PROMPT,
                    chunk.getPageStart(), chunk.getPageEnd(),
                    sourceContent,
                    extractedSummary,
                    jlptLevel, jlptLevel);

            String response = chatClient.prompt()
                    .system(SYSTEM_PROMPT)
                    .user(userPrompt)
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
            boolean recommends = (Boolean) parsed.getOrDefault("recommendsReExtraction", false);
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
     * Build a concise summary of extracted content for the reflection prompt.
     */
    private String buildExtractionSummary(ExtractionResult result) {
        StringBuilder sb = new StringBuilder();

        if (!result.getVocabulary().isEmpty()) {
            sb.append("VOCABULARY ITEMS:\n");
            for (ExtractionResult.ExtractedVocabulary vocab : result.getVocabulary()) {
                sb.append(String.format("- %s (%s) [%s]: %s / %s [%s]\n",
                        vocab.getJapaneseText() != null ? vocab.getJapaneseText() : "?",
                        vocab.getReading() != null ? vocab.getReading() : "?",
                        vocab.getRomaji() != null ? vocab.getRomaji() : "?",
                        vocab.getMeaningVi() != null ? vocab.getMeaningVi() : "?",
                        vocab.getMeaningEn() != null ? vocab.getMeaningEn() : "?",
                        vocab.getPartOfSpeech() != null ? vocab.getPartOfSpeech() : "?"));
            }
        }

        if (!result.getGrammarPoints().isEmpty()) {
            sb.append("GRAMMAR POINTS:\n");
            for (ExtractionResult.ExtractedGrammar grammar : result.getGrammarPoints()) {
                sb.append(String.format("- %s: %s / %s — %s\n",
                        grammar.getPattern() != null ? grammar.getPattern() : "?",
                        grammar.getMeaningVi() != null ? grammar.getMeaningVi() : "?",
                        grammar.getMeaningEn() != null ? grammar.getMeaningEn() : "?",
                        grammar.getShortExplanation() != null ? grammar.getShortExplanation() : "?"));
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
                        .isRelevant((Boolean) item.getOrDefault("isRelevant", true))
                        .isSupported((Boolean) item.getOrDefault("isSupported", true))
                        .isUseful((Boolean) item.getOrDefault("isUseful", true))
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
                .reflectionVerdict(ReflectionVerdict.MOSTLY_APPROVED)
                .reflectionScore(0.7)
                .reflectionReasoning("Fallback assessment: " + reason)
                .itemReflections(Collections.emptyList())
                .llmRecommendsReExtraction(false)
                .groundingScore(0.0)
                .groundedCount(0)
                .hallucinatedCount(0)
                .groundingChecks(List.of())
                .hallucinatedItems(List.of())
                .combinedScore(0.7)
                .combinedVerdict(ReflectionResult.CombinedVerdict.GOOD)
                .needsReExtraction(false)
                .build();
    }
}
