package com.fu.roleplay.ai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fu.common.ai.PromptFragment;
import com.fu.common.ai.PromptUtils;
import com.fu.roleplay.config.RoleplayProperties;
import com.fu.roleplay.entity.ConversationMessage;
import com.fu.roleplay.entity.SessionMission;
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
            OUTPUT FORMAT — STRICT:
            Return a JSON ARRAY at the top level. One element per mission.
            DO NOT wrap the array inside an outer object (no {"missions":[...]}, no {"results":[...]}).
            DO NOT return a single object — even when there is only one mission, still return an array.

            Example of CORRECT output:
            [{"missionId":"...","status":"IN_PROGRESS","progressPct":50,"reasoning":"..."}]

            Example of WRONG output (do NOT do this):
            {"missions":[{"missionId":"..."}]}

            Each array element must have:
            - missionId (String): UUID of the session mission
            - status (String): PENDING, IN_PROGRESS, COMPLETED, or FAILED
            - progressPct (Integer): 0-100 progress percentage
            - reasoning (String): Giải thích ngắn gọn bằng tiếng Việt về kết quả đánh giá nhiệm vụ

            Be generous but fair. If the learner attempts the mission topic, mark as IN_PROGRESS.
            If they clearly accomplish the criteria, mark COMPLETED.
            """;

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final RoleplayProperties roleplayProperties;

    public MissionEvaluatorAI(OpenAiChatModel chatModel, ObjectMapper objectMapper,
                               RoleplayProperties roleplayProperties) {
        this.chatClient = ChatClient.builder(chatModel).build();
        this.objectMapper = objectMapper;
        this.roleplayProperties = roleplayProperties;
    }

    public List<MissionProgress> evaluateProgress(List<ConversationMessage> history,
                                                   String latestMessage,
                                                   List<SessionMission> missions) {
        try {
            List<ConversationMessage> recentHistory = PromptUtils.slidingTail(history, roleplayProperties.getMaxEvalMessages());

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
                    .options(OpenAiChatOptions.builder().model(roleplayProperties.getEvaluatorModel()).build())
                    .system(MISSION_EVAL_SYSTEM_PROMPT)
                    .user(userPrompt.toString())
                    .call()
                    .content();

            log.debug("Mission evaluator raw response ({} missions in scope): {}", missions.size(), rawResponse);

            String cleanJson = cleanJson(rawResponse);
            List<Map<String, Object>> results = parseToList(cleanJson);

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

    /**
     * Tolerant parser: model may return a top-level array (correct), an object
     * wrapper like {"missions":[...]}/{"results":[...]}/{"evaluations":[...]}/{"data":[...]},
     * or a single object. Normalize all three to List<Map>.
     */
    private List<Map<String, Object>> parseToList(String json) throws java.io.IOException {
        com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(json);
        if (node.isArray()) {
            return objectMapper.convertValue(node,
                    new TypeReference<List<Map<String, Object>>>() {});
        }
        if (node.isObject()) {
            // Look for any embedded array field — common keys first, then any array value.
            for (String key : new String[]{"missions", "results", "evaluations", "data", "items"}) {
                com.fasterxml.jackson.databind.JsonNode arr = node.get(key);
                if (arr != null && arr.isArray()) {
                    return objectMapper.convertValue(arr,
                            new TypeReference<List<Map<String, Object>>>() {});
                }
            }
            java.util.Iterator<com.fasterxml.jackson.databind.JsonNode> it = node.elements();
            while (it.hasNext()) {
                com.fasterxml.jackson.databind.JsonNode v = it.next();
                if (v.isArray()) {
                    return objectMapper.convertValue(v,
                            new TypeReference<List<Map<String, Object>>>() {});
                }
            }
            // Single-object fallback — treat as one-element array if it has missionId.
            if (node.has("missionId")) {
                return List.of(objectMapper.convertValue(node,
                        new TypeReference<Map<String, Object>>() {}));
            }
        }
        log.warn("Mission evaluator returned unexpected JSON shape, ignoring: {}", json);
        return List.of();
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
