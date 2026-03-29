package com.fu.assignment.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fu.assignment.dto.request.AiExplanationRequest;
import com.fu.assignment.dto.response.AiExplanationResponse;
import com.fu.assignment.entity.QuizQuestion;
import com.fu.assignment.repository.QuizQuestionRepository;
import com.fu.assignment.util.MarkdownUtils;
import com.fu.common.exception.BusinessException;
import com.fu.common.exception.ResourceNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * AI service to generate explanation for a quiz question's correct answer.
 * Both an English/Japanese and a Vietnamese explanation are generated simultaneously.
 * <p>
 * Uses Spring AI ChatClient for interaction.
 */
@Service
@Slf4j
public class QuizExplanationService {

    @Value("${spring.ai.openai.chat.options.model:gpt-4o-mini}")
    private String model;

    @Value("${spring.ai.openai.chat.options.temperature:0.7}")
    private double temperature;

    @Value("classpath:prompts/quiz-explanation-system.st")
    private Resource systemPromptResource;

    @Value("classpath:prompts/quiz-explanation-user.st")
    private Resource userPromptResource;

    private final QuizQuestionRepository questionRepository;
    private final ChatClient chatClient;
    private final BeanOutputConverter<AiExplanationResponse> outputConverter;

    public QuizExplanationService(OpenAiChatModel chatModel,
                                  QuizQuestionRepository questionRepository) {
        this.questionRepository = questionRepository;
        this.chatClient = ChatClient.builder(chatModel).build();
        this.outputConverter = new BeanOutputConverter<>(AiExplanationResponse.class);
    }

    /**
     * Generate AI explanation for a quiz question and save it.
     */
    @Transactional
    public QuizQuestion generateExplanation(UUID questionId, UUID requestedBy) {
        QuizQuestion question = questionRepository.findById(questionId)
                .orElseThrow(() -> new ResourceNotFoundException("Quiz question not found: " + questionId));

        Map<String, Object> params = buildParams(question);
        log.info("Generating AI explanation for question {}", questionId);

        String rawResponse = callOpenAi(params);

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
        Map<String, Object> params = buildParams(request);
        log.info("Generating AI explanation for draft question from form");

        String rawResponse = callOpenAi(params);
        return parseResponse(rawResponse);
    }

    // ── AI API Call (using ChatClient) ────────────────────────────────────────

    private String callOpenAi(Map<String, Object> params) {
        try {
            Map<String, Object> templateModel = new java.util.HashMap<>(params);
            String formatInstruction = outputConverter.getFormat();
            templateModel.put("formatInstruction", formatInstruction);
            templateModel.put("lb", "{");
            templateModel.put("rb", "}");

            PromptTemplate systemTemplate = new PromptTemplate(systemPromptResource);
            PromptTemplate userTemplate = new PromptTemplate(userPromptResource);
            
            return chatClient.prompt()
                    .system(systemTemplate.render(Map.of("lb", "{", "rb", "}")))
                    .user(userTemplate.render(templateModel))
                    .options(OpenAiChatOptions.builder()
                            .model(this.model)
                            .temperature(temperature)
                            .build())
                    .call()
                    .content();

        } catch (Exception e) {
            log.error("AI call failed: {}", e.getMessage());
            throw new BusinessException("AI_RESPONSE_ERROR",
                    "Failed to call AI: " + e.getMessage());
        }
    }

    // ── Private Helpers ───────────────────────────────────────────────────────

    private Map<String, Object> buildParams(QuizQuestion question) {
        return buildParamsContent(
                question.getQuestionText(),
                question.getQuestionType(),
                question.getOptions(),
                question.getCorrectAnswer(),
                question.getCorrectAnswers()
        );
    }

    private Map<String, Object> buildParams(AiExplanationRequest request) {
        return buildParamsContent(
                request.getQuestionText(),
                request.getQuestionType(),
                request.getOptions(),
                request.getCorrectAnswer(),
                request.getCorrectAnswers()
        );
    }

    private Map<String, Object> buildParamsContent(String text, String type, String options, String ans, String ansJsonb) {
        return Map.of(
                "questionText", text != null ? text : "",
                "questionType", type != null ? type : "multiple_choice",
                "options", options != null ? options : "[]",
                "correctAnswer", ans != null ? ans : "",
                "correctAnswers", ansJsonb != null ? ansJsonb : "[]"
        );
    }

    private void parseAndApply(String rawResponse, QuizQuestion question) {
        AiExplanationResponse response = parseResponse(rawResponse);
        question.setExplanation(response.getExplanation());
    }

    private AiExplanationResponse parseResponse(String rawResponse) {
        try {
            AiExplanationResponse response = outputConverter.convert(rawResponse);
            if (response != null) {
                // Ensure plain text as per prompt rules
                response.setExplanation(MarkdownUtils.sanitizePlainExplanation(response.getExplanation()));
            }
            return response;
        } catch (Exception e) {
            log.error("Failed to parse AI explanation response: {}", e.getMessage());
            // Safe fallback attempt
            String cleaned = cleanJson(rawResponse);
            try {
                // Secondary fallback if BeanOutputConverter fails but JSON is mostly okay
                return new ObjectMapper().readValue(cleaned, AiExplanationResponse.class);
            } catch (Exception ex) {
                return AiExplanationResponse.builder()
                        .explanation("Lỗi xử lý kết quả từ AI: " + e.getMessage())
                        .build();
            }
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
