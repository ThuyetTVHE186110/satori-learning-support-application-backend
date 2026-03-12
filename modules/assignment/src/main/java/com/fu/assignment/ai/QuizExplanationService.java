package com.fu.assignment.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fu.assignment.dto.request.AiExplanationRequest;
import com.fu.assignment.dto.response.AiExplanationResponse;
import com.fu.assignment.entity.QuizQuestion;
import com.fu.assignment.repository.QuizQuestionRepository;
import com.fu.common.exception.ResourceNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * AI service to generate explanation for a quiz question's correct answer.
 * Both an English/Japanese and a Vietnamese explanation are generated simultaneously.
 * <p>
 * Uses RestTemplate to call OpenAI API directly (bypasses Spring AI ChatClient
 * to avoid deserialization issues with newer OpenAI response fields).
 */
@Service
@Slf4j
public class QuizExplanationService {

    private static final String OPENAI_CHAT_URL = "https://api.openai.com/v1/chat/completions";

    private static final String SYSTEM_PROMPT = """
            You are an expert Japanese language teacher.
            Your task is to explain why a given answer is correct for a Japanese language quiz question.
            
            RULES:
            1. Always respond with ONLY a valid JSON object. No markdown. No extra text.
            2. Structure:
               {
                 "explanation": "Explanation in English (or Japanese if the question is in Japanese)",
                 "explanationVi": "Giải thích bằng tiếng Việt"
               }
            3. Keep explanations concise (2-4 sentences each).
            4. Reference grammar rules, vocabulary meanings, or contextual usage.
            5. Be encouraging and educational.
            """;

    @Value("${spring.ai.openai.api-key:}")
    private String apiKey;

    @Value("${spring.ai.openai.chat.options.model:gpt-4o-mini}")
    private String model;

    private final ObjectMapper objectMapper;
    private final QuizQuestionRepository questionRepository;
    private final RestTemplate restTemplate;

    public QuizExplanationService(ObjectMapper objectMapper,
                                  QuizQuestionRepository questionRepository) {
        this.objectMapper = objectMapper;
        this.questionRepository = questionRepository;
        this.restTemplate = new RestTemplate();
    }

    /**
     * Generate AI explanation for a quiz question and save it.
     */
    @Transactional
    public QuizQuestion generateExplanation(UUID questionId, UUID requestedBy) {
        QuizQuestion question = questionRepository.findById(questionId)
                .orElseThrow(() -> new ResourceNotFoundException("Quiz question not found: " + questionId));

        String userPrompt = buildPrompt(question);
        log.info("Generating AI explanation for question {}", questionId);

        String rawResponse = callOpenAi(userPrompt);

        parseAndApply(rawResponse, question);
        question.setUpdatedBy(requestedBy);
        question.setUpdatedAt(LocalDateTime.now());

        QuizQuestion updated = questionRepository.save(question);
        log.info("AI explanation saved for question {}", questionId);
        return updated;
    }

    /**
     * Generate AI explanation for a draft question from the frontend form.
     */
    public AiExplanationResponse generateExplanationFromDraft(AiExplanationRequest request) {
        String userPrompt = buildPrompt(request);
        log.info("Generating AI explanation for draft question from form");

        String rawResponse = callOpenAi(userPrompt);
        return parseResponse(rawResponse);
    }

    // ── OpenAI API Call ──────────────────────────────────────────────────────

    private String callOpenAi(String userPrompt) {
        try {
            String requestBody = objectMapper.writeValueAsString(Map.of(
                    "model", model,
                    "temperature", 0.7,
                    "max_tokens", 1024,
                    "messages", List.of(
                            Map.of("role", "system", "content", SYSTEM_PROMPT),
                            Map.of("role", "user", "content", userPrompt)
                    )
            ));

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);

            ResponseEntity<String> response = restTemplate.exchange(
                    OPENAI_CHAT_URL, HttpMethod.POST,
                    new HttpEntity<>(requestBody, headers), String.class);

            JsonNode root = objectMapper.readTree(response.getBody());
            return root.path("choices").get(0).path("message").path("content").asText();

        } catch (HttpClientErrorException e) {
            log.error("OpenAI API error: HTTP {} - {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new com.fu.common.exception.BusinessException("AI_RESPONSE_ERROR",
                    "OpenAI API error: " + e.getResponseBodyAsString());
        } catch (Exception e) {
            log.error("OpenAI call failed: {}", e.getMessage());
            throw new com.fu.common.exception.BusinessException("AI_RESPONSE_ERROR",
                    "Failed to call OpenAI: " + e.getMessage());
        }
    }

    // ── Private Helpers ───────────────────────────────────────────────────────

    private String buildPrompt(QuizQuestion question) {
        return buildPromptContent(
                question.getQuestionText(),
                question.getQuestionJapanese(),
                question.getQuestionType(),
                question.getOptions(),
                question.getCorrectAnswer(),
                question.getCorrectAnswers()
        );
    }

    private String buildPrompt(AiExplanationRequest request) {
        return buildPromptContent(
                request.getQuestionText(),
                request.getQuestionJapanese(),
                request.getQuestionType(),
                request.getOptions(),
                request.getCorrectAnswer(),
                request.getCorrectAnswers()
        );
    }

    private String buildPromptContent(String text, String jp, String type, String options, String ans, String ansJsonb) {
        StringBuilder sb = new StringBuilder();
        sb.append("QUESTION: ").append(text).append("\n");
        if (jp != null && !jp.isBlank()) {
            sb.append("JAPANESE: ").append(jp).append("\n");
        }
        sb.append("QUESTION TYPE: ").append(type).append("\n");

        if (options != null && !options.equals("[]") && !options.isBlank()) {
            sb.append("OPTIONS: ").append(options).append("\n");
        }
        if (ans != null && !ans.isBlank()) {
            sb.append("CORRECT ANSWER: ").append(ans).append("\n");
        }
        if (ansJsonb != null && !ansJsonb.isBlank()) {
            sb.append("CORRECT ANSWERS (JSONB): ").append(ansJsonb).append("\n");
        }

        sb.append("\nPlease explain why the correct answer is correct.");
        return sb.toString();
    }

    private void parseAndApply(String rawResponse, QuizQuestion question) {
        AiExplanationResponse response = parseResponse(rawResponse);
        question.setExplanation(response.getExplanation());
        question.setExplanationVi(response.getExplanationVi());
    }

    private AiExplanationResponse parseResponse(String rawResponse) {
        try {
            String cleanJson = cleanJson(rawResponse);
            JsonNode node = objectMapper.readTree(cleanJson);
            return AiExplanationResponse.builder()
                    .explanation(node.path("explanation").asText(null))
                    .explanationVi(node.path("explanationVi").asText(null))
                    .build();
        } catch (Exception e) {
            log.error("Failed to parse AI explanation response: {}", e.getMessage());
            return AiExplanationResponse.builder()
                    .explanation(rawResponse)
                    .explanationVi("Lỗi xử lý JSON từ AI: " + e.getMessage())
                    .build();
        }
    }

    private String cleanJson(String raw) {
        if (raw == null) return "{}";
        String cleaned = raw.trim();
        if (cleaned.startsWith("```json")) cleaned = cleaned.substring(7);
        else if (cleaned.startsWith("```")) cleaned = cleaned.substring(3);
        if (cleaned.endsWith("```")) cleaned = cleaned.substring(0, cleaned.length() - 3);
        return cleaned.trim();
    }
}
