package com.fu.curriculumimport.service.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fu.curriculumimport.service.ingestion.DocumentChunk;
import com.fu.curriculumimport.service.ingestion.MnNSectionSplitter;
import com.fu.curriculumimport.util.LlmResponseUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Content Classifier Agent — First step in the Agentic RAG pipeline.
 *
 * Analyzes a document chunk and classifies its content type:
 * - VOCABULARY: Primarily word lists, vocabulary tables, kanji lists
 * - GRAMMAR: Grammar patterns, conjugation rules, sentence structures
 * - MIXED: Contains both vocabulary and grammar content
 * - CONVERSATION: Dialogue, conversation practice
 * - EXERCISE: Practice exercises, quizzes, drills
 * - OTHER: Table of contents, index, appendix, etc.
 *
 * This classification routes the chunk to the appropriate specialized
 * extraction agent, improving extraction quality.
 *
 * Reference: Agentic RAG Orchestrator-Worker pattern (classifier → router)
 */
@Service
@Slf4j
public class ContentClassifierAgent {

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;

    public ContentClassifierAgent(OpenAiChatModel chatModel, ObjectMapper objectMapper) {
        this.chatClient = ChatClient.builder(chatModel).build();
        this.objectMapper = objectMapper;
    }

    private static final String SYSTEM_PROMPT = """
            You are a Japanese language textbook content classifier.
            Analyze the given text and classify what type of educational content it contains.
            
            You MUST respond ONLY with valid JSON. No explanations, no markdown code fences.
            """;

    private static final String CLASSIFICATION_PROMPT = """
            Classify the following Japanese language textbook content from pages %d-%d.
            
            Text:
            ---
            %s
            ---
            
            Return JSON with this EXACT structure:
            {
              "contentType": "VOCABULARY" | "GRAMMAR" | "MIXED" | "CONVERSATION" | "EXERCISE" | "OTHER",
              "confidence": 0.0-1.0,
              "reasoning": "Brief explanation of classification",
              "estimatedVocabCount": 0,
              "estimatedGrammarCount": 0,
              "containsExercises": false
            }
            
            Classification rules:
            - VOCABULARY: Word lists, vocabulary tables (>70%% vocabulary content)
            - GRAMMAR: Grammar rules, conjugation tables, sentence patterns (>70%% grammar)
            - MIXED: Balanced mix of vocabulary and grammar
            - CONVERSATION: Dialogue practice, conversation examples
            - EXERCISE: Drills, quizzes, practice problems
            - OTHER: Non-educational content (TOC, index, copyright)
            """;

    /**
     * Classify the content type of a document chunk.
     *
     * <p><b>Fast path</b>: if the chunk already carries a {@code sectionType} set by
     * {@link MnNSectionSplitter} (structural analysis), that signal is mapped directly
     * to a {@link ClassificationResult.ContentType} with high confidence (0.95).
     * This avoids an LLM call for the ~60–70% of chunks whose type is unambiguous
     * from MnN's rigid section headers.
     *
     * <p><b>LLM path</b>: used for {@code UNKNOWN} sections or chunks that were not
     * processed by the section splitter (e.g. character-based fallback chunks).
     *
     * @param chunk the document chunk to classify
     * @return classification result with content type and confidence
     */
    public ClassificationResult classify(DocumentChunk chunk) {
        // Fast-path: trust structural section type from MnNSectionSplitter
        ClassificationResult.ContentType structuralType = mapSectionType(chunk.getSectionType());
        if (structuralType != null) {
            log.debug("Chunk {} fast-classified as {} via sectionType '{}'",
                    chunk.getChunkIndex(), structuralType, chunk.getSectionType());
            return ClassificationResult.builder()
                    .contentType(structuralType)
                    .confidence(0.95)
                    .reasoning("Structurally classified from MnN section header: " + chunk.getSectionType())
                    .build();
        }

        // LLM path: chunk sectionType is null or UNKNOWN
        try {
            String userPrompt = String.format(CLASSIFICATION_PROMPT,
                    chunk.getPageStart(), chunk.getPageEnd(),
                    truncateForClassification(chunk.getContent()));

            String response = chatClient.prompt()
                    .system(SYSTEM_PROMPT)
                    .user(userPrompt)
                    .call()
                    .content();

            return parseClassificationResponse(response);

        } catch (Exception e) {
            log.error("Classification failed for chunk {}: {}", chunk.getChunkIndex(), e.getMessage());
            return ClassificationResult.fallback();
        }
    }

    /**
     * Map a structural section type (from MnNSectionSplitter) to a ContentType.
     * Returns {@code null} when the section type is null, UNKNOWN, or unrecognized —
     * signalling that LLM classification is needed.
     */
    private ClassificationResult.ContentType mapSectionType(String sectionType) {
        if (sectionType == null || MnNSectionSplitter.UNKNOWN.equals(sectionType)) return null;
        return switch (sectionType) {
            case MnNSectionSplitter.VOCABULARY          -> ClassificationResult.ContentType.VOCABULARY;
            case MnNSectionSplitter.GRAMMAR_PATTERN,
                 MnNSectionSplitter.GRAMMAR_EXPLANATION -> ClassificationResult.ContentType.GRAMMAR;
            case MnNSectionSplitter.DIALOG              -> ClassificationResult.ContentType.CONVERSATION;
            case MnNSectionSplitter.EXAMPLE             -> ClassificationResult.ContentType.MIXED;
            case MnNSectionSplitter.EXERCISE            -> ClassificationResult.ContentType.EXERCISE;
            default                                     -> null;
        };
    }

    private ClassificationResult parseClassificationResponse(String jsonResponse) {
        try {
            String cleaned = LlmResponseUtils.cleanJson(jsonResponse);
            Map<String, Object> parsed = objectMapper.readValue(cleaned, new TypeReference<>() {});

            String contentTypeStr = (String) parsed.getOrDefault("contentType", "MIXED");
            ClassificationResult.ContentType contentType;
            try {
                contentType = ClassificationResult.ContentType.valueOf(contentTypeStr);
            } catch (IllegalArgumentException e) {
                contentType = ClassificationResult.ContentType.MIXED;
            }

            return ClassificationResult.builder()
                    .contentType(contentType)
                    .confidence(((Number) parsed.getOrDefault("confidence", 0.5)).doubleValue())
                    .reasoning((String) parsed.getOrDefault("reasoning", ""))
                    .estimatedVocabCount(((Number) parsed.getOrDefault("estimatedVocabCount", 0)).intValue())
                    .estimatedGrammarCount(((Number) parsed.getOrDefault("estimatedGrammarCount", 0)).intValue())
                    .containsExercises((Boolean) parsed.getOrDefault("containsExercises", false))
                    .build();

        } catch (Exception e) {
            log.warn("Failed to parse classification response: {}", e.getMessage());
            return ClassificationResult.fallback();
        }
    }

    /**
     * Truncate content for classification (only need first ~500 chars to classify).
     */
    private String truncateForClassification(String content) {
        if (content.length() <= 800) return content;
        return content.substring(0, 400) + "\n...[truncated]...\n" +
                content.substring(content.length() - 400);
    }

}
