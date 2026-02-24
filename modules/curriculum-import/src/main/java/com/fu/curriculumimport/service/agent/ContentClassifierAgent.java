package com.fu.curriculumimport.service.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fu.curriculumimport.service.ingestion.DocumentChunk;
import lombok.RequiredArgsConstructor;
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
@RequiredArgsConstructor
public class ContentClassifierAgent {

    private final OpenAiChatModel chatModel;
    private final ObjectMapper objectMapper;

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
     * @param chunk the document chunk to classify
     * @return classification result with content type and confidence
     */
    public ClassificationResult classify(DocumentChunk chunk) {
        try {
            String userPrompt = String.format(CLASSIFICATION_PROMPT,
                    chunk.getPageStart(), chunk.getPageEnd(),
                    truncateForClassification(chunk.getContent()));

            ChatClient chatClient = ChatClient.builder(chatModel).build();
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

    private ClassificationResult parseClassificationResponse(String jsonResponse) {
        try {
            String cleaned = cleanJson(jsonResponse);
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

    private String cleanJson(String json) {
        String cleaned = json.strip();
        if (cleaned.startsWith("```json")) cleaned = cleaned.substring(7);
        if (cleaned.startsWith("```")) cleaned = cleaned.substring(3);
        if (cleaned.endsWith("```")) cleaned = cleaned.substring(0, cleaned.length() - 3);
        return cleaned.strip();
    }
}
