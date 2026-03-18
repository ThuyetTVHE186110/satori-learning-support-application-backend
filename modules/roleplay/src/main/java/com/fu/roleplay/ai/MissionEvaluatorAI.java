package com.fu.roleplay.ai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fu.common.ai.PromptFragment;
import com.fu.common.ai.PromptUtils;
import com.fu.roleplay.dto.entity.ConversationMessage;
import com.fu.roleplay.dto.entity.SessionMission;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class MissionEvaluatorAI {

    private static final String MISSION_EVAL_SYSTEM_PROMPT =
            "You are an AI mission evaluator for a Japanese language learning roleplay session.\n" +
            "Given the conversation history and the latest user message, evaluate the progress of each mission.\n\n" +
            PromptFragment.EVALUATOR_INJECTION_GUARD + "\n\n" +
            PromptFragment.VIETNAMESE_FIELDS + "\n\n" +
            PromptFragment.JSON_ONLY + "\n" +
            """
            Each element must have:
            - missionId (String): UUID of the session mission
            - status (String): PENDING, IN_PROGRESS, COMPLETED, or FAILED
            - progressPct (Integer): 0-100 progress percentage
            - reasoning (String): Giải thích ngắn gọn bằng tiếng Việt về kết quả đánh giá nhiệm vụ

            Be generous but fair. If the learner attempts the mission topic, mark as IN_PROGRESS.
            If they clearly accomplish the criteria, mark COMPLETED.
            """;

    private static final String EVALUATOR_MODEL = "gpt-4.1-mini";
    private static final int MAX_EVAL_MESSAGES = 20;

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;

    public MissionEvaluatorAI(OpenAiChatModel chatModel, ObjectMapper objectMapper) {
        this.chatClient = ChatClient.builder(chatModel).build();
        this.objectMapper = objectMapper;
    }

    public List<MissionProgress> evaluateProgress(List<ConversationMessage> history,
                                                   String latestMessage,
                                                   List<SessionMission> missions) {
        try {
            List<ConversationMessage> recentHistory = PromptUtils.slidingTail(history, MAX_EVAL_MESSAGES);

            StringBuilder userPrompt = new StringBuilder();
            userPrompt.append("CONVERSATION HISTORY:\n");
            for (ConversationMessage msg : recentHistory) {
                String role = msg.getRole() == ConversationMessage.MessageRole.USER ? "Learner" : "AI";
                userPrompt.append(role).append(": ").append(msg.getContent()).append("\n");
            }
            userPrompt.append("Learner (latest): ").append(latestMessage).append("\n\n");

            userPrompt.append("MISSIONS TO EVALUATE:\n");
            for (SessionMission m : missions) {
                userPrompt.append("- ID: ").append(m.getId())
                        .append(", Title: ").append(m.getTitle())
                        .append(", Type: ").append(m.getMissionType())
                        .append(", Criteria: ").append(m.getSuccessCriteria())
                        .append(", Current Status: ").append(m.getStatus())
                        .append(", Current Progress: ").append(m.getProgressPct()).append("%\n");
            }

            String rawResponse = chatClient.prompt()
                    .options(OpenAiChatOptions.builder().model(EVALUATOR_MODEL).build())
                    .system(MISSION_EVAL_SYSTEM_PROMPT)
                    .user(userPrompt.toString())
                    .call()
                    .content();

            String cleanJson = cleanJson(rawResponse);
            List<Map<String, Object>> results = objectMapper.readValue(cleanJson,
                    new TypeReference<List<Map<String, Object>>>() {});

            List<MissionProgress> progressList = new ArrayList<>();
            for (Map<String, Object> r : results) {
                progressList.add(MissionProgress.builder()
                        .missionId(r.get("missionId").toString())
                        .status(r.get("status").toString())
                        .progressPct(r.get("progressPct") instanceof Number n ? n.intValue() : 0)
                        .reasoning(r.getOrDefault("reasoning", "").toString())
                        .build());
            }
            return progressList;

        } catch (NonTransientAiException e) {
            log.warn("Mission evaluation skipped - AI unavailable (quota/billing): {}", e.getMessage());
            return new ArrayList<>();
        } catch (Exception e) {
            log.error("Mission evaluation failed: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    private String cleanJson(String raw) {
        return PromptUtils.cleanMarkdownFences(raw, "[]");
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class MissionProgress {
        private String missionId;
        private String status;
        private int progressPct;
        private String reasoning;
    }
}
