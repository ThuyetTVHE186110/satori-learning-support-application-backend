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
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
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

    private static final String SYSTEM_PROMPT = """
            You are a professional and dedicated Japanese language teacher.
            Your task is to provide a high-quality explanation for why a specific answer is correct in a Japanese language quiz.
            
            CORE RULES:
            1. You MUST respond strictly with a valid JSON object.
            2. JSON Structure:
               {
                 "explanation": "Detailed explanation in Vietnamese",
               }
            3. OUTPUT LANGUAGE:  The 'explanation' field MUST be written in VIETNAMESE.
            
            EXPLANATION GUIDELINES (High Quality):
            - Keep the explanation concise yet comprehensive (3-5 sentences).
            - Clearly identify the grammar pattern, vocabulary meaning, or situational context used in the question.
            - Explain the logical reasoning behind the correct answer.
            - If applicable, briefly contrast the correct choice with distractors (wrong options) to prevent common student mistakes.
            - Maintain a polite, educational, and encouraging tone.
            
            FORMATTING RULES (STRICT):
            - Use PLAIN TEXT ONLY.
            - DO NOT use Markdown (no **bold**, *italic*, etc.).
            - DO NOT use HTML tags (no <ruby>, <rt>, <b>, <i>, etc.).
            - DO NOT use special decorative characters.
            """;

    @Value("${spring.ai.openai.chat.options.model:gpt-4o-mini}")
    private String model;

    @Value("${spring.ai.openai.chat.options.temperature:0.7}")
    private double temperature;

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

    // ── AI API Call (using ChatClient) ────────────────────────────────────────

    private String callOpenAi(String userPrompt) {
        try {
            String formatInstruction = outputConverter.getFormat();
            
            return chatClient.prompt()
                    .system(SYSTEM_PROMPT)
                    .user(userPrompt + "\n\n" + formatInstruction)
                    .options(OpenAiChatOptions.builder()
                            .model(model)
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

    private String buildPrompt(QuizQuestion question) {
        return buildPromptContent(
                question.getQuestionText(),
                question.getQuestionType(),
                question.getOptions(),
                question.getCorrectAnswer(),
                question.getCorrectAnswers()
        );
    }

    private String buildPrompt(AiExplanationRequest request) {
        return buildPromptContent(
                request.getQuestionText(),
                request.getQuestionType(),
                request.getOptions(),
                request.getCorrectAnswer(),
                request.getCorrectAnswers()
        );
    }

    private String buildPromptContent(String text, String type, String options, String ans, String ansJsonb) {
        StringBuilder sb = new StringBuilder();
        sb.append("QUESTION: ").append(text).append("\n");

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
