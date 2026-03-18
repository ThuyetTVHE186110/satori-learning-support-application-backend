package com.fu.roleplay.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fu.common.ai.PromptFragment;
import com.fu.common.ai.PromptUtils;
import com.fu.roleplay.dto.response.SessionEvaluationResponse;
import com.fu.roleplay.dto.entity.ConversationMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
public class SessionEvaluatorAI {

    private static final String SESSION_EVAL_SYSTEM_PROMPT =
            "You are an AI language evaluator assessing a Japanese conversation roleplay session.\n" +
            "Analyze the full conversation and provide a comprehensive language evaluation.\n\n" +
            PromptFragment.EVALUATOR_INJECTION_GUARD + "\n\n" +
            PromptFragment.VIETNAMESE_FIELDS + "\n\n" +
            PromptFragment.JSON_ONLY + "\n" +
            """
            The object must have:
            - fluencyScore (Number): 0-100, how smoothly the learner communicates
            - accuracyScore (Number): 0-100, grammatical and vocabulary correctness
            - vocabularyScore (Number): 0-100, range and appropriateness of vocabulary used
            - grammarScore (Number): 0-100, correctness of grammar patterns used
            - strengths (Array<String>): 2-4 điểm mạnh cụ thể quan sát được (viết bằng tiếng Việt)
            - improvements (Array<String>): 2-4 điểm cần cải thiện cụ thể và khả thi (viết bằng tiếng Việt)
            - summary (String): Nhận xét tổng quan ngắn gọn, khích lệ (2-3 câu, viết bằng tiếng Việt)

            Be constructive and encouraging. Focus on what the learner did well and specific actionable improvements.
            """;

    private static final int MAX_EVAL_MESSAGES = 40;

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;

    public SessionEvaluatorAI(OpenAiChatModel chatModel, ObjectMapper objectMapper) {
        this.chatClient = ChatClient.builder(chatModel).build();
        this.objectMapper = objectMapper;
    }

    public SessionEvaluationResponse.LanguageEvaluationDetail evaluateSession(
            List<ConversationMessage> messages) {
        try {
            // Prune context: keep first 5 messages (opening context) + last messages up to MAX_EVAL_MESSAGES
            List<ConversationMessage> prunedMessages;
            if (messages.size() > MAX_EVAL_MESSAGES) {
                List<ConversationMessage> head = messages.subList(0, 5);
                List<ConversationMessage> tail = messages.subList(messages.size() - (MAX_EVAL_MESSAGES - 5), messages.size());
                prunedMessages = new ArrayList<>(head);
                prunedMessages.add(ConversationMessage.builder()
                        .role(ConversationMessage.MessageRole.ASSISTANT)
                        .content("[... " + (messages.size() - MAX_EVAL_MESSAGES) + " earlier messages omitted ...]")
                        .build());
                prunedMessages.addAll(tail);
            } else {
                prunedMessages = messages;
            }

            StringBuilder userPrompt = new StringBuilder();
            userPrompt.append("FULL CONVERSATION:\n");
            for (ConversationMessage msg : prunedMessages) {
                String role = msg.getRole() == ConversationMessage.MessageRole.USER ? "Learner" : "AI";
                userPrompt.append(role).append(": ").append(msg.getContent());
                if (msg.getCorrections() != null && !msg.getCorrections().equals("[]")) {
                    userPrompt.append(" [corrections: ").append(msg.getCorrections()).append("]");
                }
                userPrompt.append("\n");
            }

            String rawResponse = chatClient.prompt()
                    .system(SESSION_EVAL_SYSTEM_PROMPT)
                    .user(userPrompt.toString())
                    .call()
                    .content();

            String cleanJson = cleanJson(rawResponse);
            JsonNode result = objectMapper.readTree(cleanJson);

            List<String> strengths = new ArrayList<>();
            result.path("strengths").forEach(s -> strengths.add(s.asText()));
            List<String> improvements = new ArrayList<>();
            result.path("improvements").forEach(i -> improvements.add(i.asText()));

            return SessionEvaluationResponse.LanguageEvaluationDetail.builder()
                    .fluencyScore(toBigDecimal(result.path("fluencyScore").asDouble(0)))
                    .accuracyScore(toBigDecimal(result.path("accuracyScore").asDouble(0)))
                    .vocabularyScore(toBigDecimal(result.path("vocabularyScore").asDouble(0)))
                    .grammarScore(toBigDecimal(result.path("grammarScore").asDouble(0)))
                    .strengths(strengths)
                    .improvements(improvements)
                    .summary(result.path("summary").asText(""))
                    .build();

        } catch (Exception e) {
            log.error("Session evaluation failed: {}", e.getMessage());
            return SessionEvaluationResponse.LanguageEvaluationDetail.builder()
                    .fluencyScore(BigDecimal.ZERO)
                    .accuracyScore(BigDecimal.ZERO)
                    .vocabularyScore(BigDecimal.ZERO)
                    .grammarScore(BigDecimal.ZERO)
                    .strengths(List.of())
                    .improvements(List.of("Không thể đánh giá phiên hội thoại này"))
                    .summary("Đánh giá không khả dụng cho phiên hội thoại này.")
                    .build();
        }
    }

    private BigDecimal toBigDecimal(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
    }

    private String cleanJson(String raw) {
        return PromptUtils.cleanMarkdownFences(raw, "{}");
    }
}
