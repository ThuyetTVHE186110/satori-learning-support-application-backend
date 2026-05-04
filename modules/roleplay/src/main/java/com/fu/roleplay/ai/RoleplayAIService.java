package com.fu.roleplay.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fu.common.ai.PromptUtils;
import com.fu.infrastructure.learner.LearningInsightPoint;
import com.fu.infrastructure.learner.SkillScore;
import com.fu.roleplay.config.RoleplayProperties;
import com.fu.roleplay.entity.ConversationMessage;
import com.fu.roleplay.entity.ConversationSession;
import com.fu.roleplay.entity.SessionMission;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

@Service
@Slf4j
public class RoleplayAIService {

    private static final String REDIS_KEY_PREFIX = "roleplay:history:";

    private final ChatClient chatClient;
    private final RedisTemplate<String, Object> redisTemplate;
    private final RoleplayPromptBuilder promptBuilder;
    private final RoleplayProperties roleplayProperties;
    private final ObjectMapper objectMapper;

    public RoleplayAIService(OpenAiChatModel chatModel,
                              RedisTemplate<String, Object> redisTemplate,
                              RoleplayPromptBuilder promptBuilder,
                              RoleplayProperties roleplayProperties,
                              ObjectMapper objectMapper) {
        this.chatClient = ChatClient.builder(chatModel).build();
        this.redisTemplate = redisTemplate;
        this.promptBuilder = promptBuilder;
        this.roleplayProperties = roleplayProperties;
        this.objectMapper = objectMapper;
    }

    /**
     * Builds the system prompt for a structured roleplay session.
     *
     * @param session     current conversation session (unused here but kept for call-site clarity)
     * @param aiRole      identity of the AI character (e.g. "a Japanese shopkeeper")
     * @param learnerRole identity of the learner in the scenario (e.g. "a customer")
     * @param scenario    narrative description of the roleplay setting
     * @param jlptLevel   target JLPT level
     * @param missions    session missions the learner must accomplish
     * @see RoleplayPromptBuilder#buildSystemPrompt
     */
    public String buildSystemPrompt(ConversationSession session, String aiRole, String learnerRole,
                                    String scenario, String jlptLevel, List<SessionMission> missions) {
        return promptBuilder.buildSystemPrompt(aiRole, learnerRole, scenario, jlptLevel, missions);
    }

    /**
     * Overload that includes learner insights for personalized roleplay.
     */
    public String buildSystemPrompt(ConversationSession session, String aiRole, String learnerRole,
                                    String scenario, String jlptLevel, List<SessionMission> missions,
                                    List<SkillScore> skillScores,
                                    List<LearningInsightPoint> weakPoints,
                                    List<LearningInsightPoint> strongPoints) {
        return promptBuilder.buildSystemPrompt(aiRole, learnerRole, scenario, jlptLevel, missions,
                skillScores, weakPoints, strongPoints);
    }

    /**
     * Builds the system prompt for a FREE_TALK SETUP phase.
     * @see RoleplayPromptBuilder#buildSetupSystemPrompt
     */
    public String buildSetupSystemPrompt(String jlptLevel) {
        return promptBuilder.buildSetupSystemPrompt(jlptLevel);
    }

    public String generateResponse(UUID sessionId, String systemPrompt, List<ConversationMessage> history,
                                    String userMessage) {
        return generateResponse(sessionId, systemPrompt, history, userMessage, null);
    }

    /**
     * Overload that prepends a server-rendered world-state block to the user-side payload.
     * Used for persistent-memory roleplay where established facts must survive context-window
     * truncation. {@code worldStateBlock} may be {@code null} or empty when there is nothing
     * to inject yet (early in the session).
     */
    public String generateResponse(UUID sessionId, String systemPrompt, List<ConversationMessage> history,
                                    String userMessage, String worldStateBlock) {
        try {
            List<ConversationMessage> hybridHistory = buildHybridContextWindow(history);

            StringBuilder conversationHistory = new StringBuilder();
            if (worldStateBlock != null && !worldStateBlock.isBlank()) {
                conversationHistory.append(worldStateBlock);
                if (!worldStateBlock.endsWith("\n")) conversationHistory.append("\n");
            }
            conversationHistory.append("<conversation_history>\n");
            for (ConversationMessage msg : hybridHistory) {
                String role = msg.getRole() == ConversationMessage.MessageRole.USER ? "Learner" : "AI";
                conversationHistory.append(role).append(": ").append(sanitizeUserInput(msg.getContent())).append("\n");
            }
            conversationHistory.append("Learner: ").append(sanitizeUserInput(userMessage)).append("\n");
            conversationHistory.append("</conversation_history>\n");

            String rawResponse = chatClient.prompt()
                    .options(OpenAiChatOptions.builder().model(roleplayProperties.getModelName()).build())
                    .system(systemPrompt)
                    .user(conversationHistory.toString())
                    .call()
                    .content();

            return cleanJson(rawResponse);
        } catch (NonTransientAiException e) {
            log.warn("AI service unavailable for session {} (quota/billing): {}", sessionId, e.getMessage());
            return defaultArrayResponse();
        } catch (Exception e) {
            log.error("Failed to generate roleplay response for session {}: {}", sessionId, e.getMessage());
            return defaultArrayResponse();
        }
    }

    public String generateOpeningMessage(String systemPrompt,
                                          String aiRole, String learnerRole, String scenario) {
        try {
            String rawResponse = chatClient.prompt()
                    .options(OpenAiChatOptions.builder().model(roleplayProperties.getModelName()).build())
                    .system(systemPrompt)
                    .user("""
                            Open the roleplay. Return the JSON array with MISSION_INTRO + SCENE_INTRO + DIALOGUE per the format in your instructions.

                            For the DIALOGUE segment:
                            - Speak as YOUR character (the role assigned to you in YOUR IDENTITY).
                            - Your line is what YOU say first to the learner — a greeting, question, or natural opener for your role.
                            - DO NOT emit a sentence from the learner's task list. Those are the LEARNER's lines, not yours.
                            - DO NOT speak on behalf of the learner.
                            """)
                    .call()
                    .content();

            return cleanJson(rawResponse);
        } catch (NonTransientAiException e) {
            log.warn("AI service unavailable for opening message (quota/billing): {}", e.getMessage());
            return defaultOpeningArrayResponse(aiRole, learnerRole, scenario);
        } catch (Exception e) {
            log.error("Failed to generate opening message: {}", e.getMessage());
            return defaultOpeningArrayResponse(aiRole, learnerRole, scenario);
        }
    }

    public String generateSetupOpening(String setupSystemPrompt) {
        try {
            String rawResponse = chatClient.prompt()
                    .options(OpenAiChatOptions.builder().model(roleplayProperties.getModelName()).build())
                    .system(setupSystemPrompt)
                    .user("Start by asking the learner what scenario they want to practice.")
                    .call()
                    .content();

            return cleanJson(rawResponse);
        } catch (NonTransientAiException e) {
            log.warn("AI service unavailable for setup opening (quota/billing): {}", e.getMessage());
            return defaultSetupResponse();
        } catch (Exception e) {
            log.error("Failed to generate setup opening: {}", e.getMessage());
            return defaultSetupResponse();
        }
    }

    public void cacheSystemPrompt(UUID sessionId, String systemPrompt) {
        try {
            String key = REDIS_KEY_PREFIX + sessionId + ":system";
            redisTemplate.opsForValue().set(key, systemPrompt, Duration.ofHours(roleplayProperties.getRedisSessionTtlHours()));
        } catch (Exception e) {
            log.warn("Failed to cache system prompt for session {}: {}", sessionId, e.getMessage());
        }
    }

    public String getCachedSystemPrompt(UUID sessionId) {
        try {
            String key = REDIS_KEY_PREFIX + sessionId + ":system";
            Object value = redisTemplate.opsForValue().get(key);
            return value != null ? value.toString() : null;
        } catch (Exception e) {
            log.warn("Failed to get cached system prompt for session {}: {}", sessionId, e.getMessage());
            return null;
        }
    }

    /**
     * Generates a context-aware hint sentence the learner could say next.
     * Called when the client fires a hint request (e.g. after user silence).
     * Returns a single HINT-type segment JSON array.
     */
    public String generateHintResponse(UUID sessionId, String systemPrompt,
                                        List<ConversationMessage> history,
                                        List<SessionMission> missions) {
        try {
            List<ConversationMessage> hybridHistory = buildHybridContextWindow(history);

            StringBuilder sb = new StringBuilder();
            sb.append("<conversation_history>\n");
            for (ConversationMessage msg : hybridHistory) {
                String role = msg.getRole() == ConversationMessage.MessageRole.USER ? "Learner" : "AI";
                sb.append(role).append(": ").append(sanitizeUserInput(msg.getContent())).append("\n");
            }
            sb.append("</conversation_history>\n\n");

            sb.append("PENDING MISSIONS:\n");
            missions.stream()
                    .filter(m -> m.getStatus() == SessionMission.MissionStatus.PENDING
                            || m.getStatus() == SessionMission.MissionStatus.IN_PROGRESS)
                    .forEach(m -> sb.append("- ").append(m.getTitle()).append("\n"));

            sb.append("\nThe learner has been silent. Suggest ONE natural Japanese sentence they could say next ")
              .append("to advance the conversation toward their pending missions. ")
              .append("Return ONLY a JSON array with a single HINT object: ")
              .append("[{\"type\":\"HINT\",\"japanese_content\":\"...\",\"translation\":\"... (tiếng Việt)\",\"romaji\":\"...\"}]");

            String rawResponse = chatClient.prompt()
                    .options(OpenAiChatOptions.builder().model(roleplayProperties.getModelName()).build())
                    .system(systemPrompt)
                    .user(sb.toString())
                    .call()
                    .content();

            return cleanJson(rawResponse);
        } catch (NonTransientAiException e) {
            log.warn("AI unavailable for hint (session {}): {}", sessionId, e.getMessage());
            return defaultHintResponse();
        } catch (Exception e) {
            log.error("Failed to generate hint for session {}: {}", sessionId, e.getMessage());
            return defaultHintResponse();
        }
    }

    private String defaultHintResponse() {
        return "[{\"type\":\"HINT\",\"japanese_content\":\"何か言ってみましょう。\","
                + "\"translation\":\"Hãy thử nói điều gì đó.\",\"romaji\":\"Nanika itte mimashou.\"}]";
    }

    public void clearSessionCache(UUID sessionId) {
        try {
            String systemKey = REDIS_KEY_PREFIX + sessionId + ":system";
            redisTemplate.delete(systemKey);
        } catch (Exception e) {
            log.warn("Failed to clear cache for session {}: {}", sessionId, e.getMessage());
        }
    }

    // Patterns for prompt injection prevention (case-insensitive)
    private static final Pattern INJECTION_PATTERN = Pattern.compile(
            "(?i)(</?system_instructions>|</?conversation_history>|</?hidden_objectives>|"
            + "SYSTEM_INSTRUCTIONS|END_SYSTEM_INSTRUCTIONS|CONVERSATION_HISTORY|END_CONVERSATION_HISTORY|"
            + "HIDDEN OBJECTIVES|<<<|>>>)", Pattern.CASE_INSENSITIVE);

    /**
     * Hybrid context window: pinned opening messages (scenario setup) + sliding tail of recent turns.
     * Prevents attention dilution ("Lost in the Middle") from dropping the scenario anchor on long sessions.
     * If total history ≤ window, returns full history unchanged.
     */
    private List<ConversationMessage> buildHybridContextWindow(List<ConversationMessage> history) {
        int maxWindow = roleplayProperties.getMaxContextMessages();
        int pinned = Math.max(0, roleplayProperties.getPinnedContextMessages());
        if (history.size() <= maxWindow) {
            return history;
        }
        int pinnedEffective = Math.min(pinned, history.size());
        int tailSize = Math.max(0, maxWindow - pinnedEffective);
        List<ConversationMessage> head = history.subList(0, pinnedEffective);
        List<ConversationMessage> tail = history.subList(history.size() - tailSize, history.size());
        // Deduplicate: if head and tail overlap (very short history, handled above), keep head then non-overlapping tail.
        int headLast = pinnedEffective - 1;
        int tailFirst = history.size() - tailSize;
        List<ConversationMessage> combined = new java.util.ArrayList<>(pinnedEffective + tailSize);
        combined.addAll(head);
        if (tailFirst > headLast + 1) {
            // Gap between pinned head and tail — inject a ellipsis marker as a synthetic SYSTEM message.
            ConversationMessage ellipsis = ConversationMessage.builder()
                    .role(ConversationMessage.MessageRole.SYSTEM)
                    .content("[... earlier turns omitted ...]")
                    .sequenceNumber(-1)
                    .build();
            combined.add(ellipsis);
        }
        combined.addAll(tail);
        return combined;
    }

    private String sanitizeUserInput(String input) {
        if (input == null) return "";
        return INJECTION_PATTERN.matcher(input).replaceAll("");
    }

    private String cleanJson(String raw) {
        return PromptUtils.cleanMarkdownFences(raw, defaultArrayResponse());
    }

    // Fallback: single-element array with a DIALOGUE segment
    private static final String[] FALLBACK_DIALOGUE_SEGMENTS = {
            "[{\"type\":\"DIALOGUE\",\"japanese_content\":\"すみません、もう一度お願いします。\",\"translation\":\"Xin lỗi, bạn có thể nói lại không?\",\"romaji\":\"Sumimasen, mou ichido onegai shimasu.\",\"corrections\":[]}]",
            "[{\"type\":\"DIALOGUE\",\"japanese_content\":\"ごめんなさい、よく聞こえませんでした。もう一度言ってください。\",\"translation\":\"Xin lỗi, tôi không nghe rõ. Vui lòng nói lại.\",\"romaji\":\"Gomen nasai, yoku kikoemasen deshita. Mou ichido itte kudasai.\",\"corrections\":[]}]",
            "[{\"type\":\"DIALOGUE\",\"japanese_content\":\"少し待ってください。もう一度話してもらえますか？\",\"translation\":\"Vui lòng đợi một chút. Bạn có thể nói lại không?\",\"romaji\":\"Sukoshi matte kudasai. Mou ichido hanashite moraemasu ka?\",\"corrections\":[]}]"
    };

    private String defaultArrayResponse() {
        return FALLBACK_DIALOGUE_SEGMENTS[ThreadLocalRandom.current().nextInt(FALLBACK_DIALOGUE_SEGMENTS.length)];
    }

    /**
     * Context-aware fallback for the opening message when OpenAI is unavailable.
     * <p>
     * The previous fallback hardcoded a shopkeeper DIALOGUE — wrong role for any non-retail
     * scenario, and missing the {@code voice} field so the session voice never locked.
     * <p>
     * This version returns 3 segments with EMPTY {@code japanese_content} (so TTS does not
     * synthesize misleading audio) and Vietnamese-only narration that:
     * <ul>
     *   <li>States the assigned roles so the learner knows the scenario stayed correct;</li>
     *   <li>Echoes the scenario description if available;</li>
     *   <li>Emits a {@code SYSTEM} segment so FE can render a "AI tạm thời không khả dụng"
     *       banner instead of treating the message as in-character dialogue.</li>
     * </ul>
     * No DIALOGUE segment is emitted — the learner's first message will trigger a fresh
     * generateResponse call which can recover when the upstream is healthy.
     */
    private String defaultOpeningArrayResponse(String aiRole, String learnerRole, String scenario) {
        String resolvedAi = (aiRole != null && !aiRole.isBlank()) ? aiRole : "nhân vật người Nhật";
        String resolvedLearner = (learnerRole != null && !learnerRole.isBlank()) ? learnerRole : "người học";
        String resolvedScenario = (scenario != null && !scenario.isBlank())
                ? scenario : "(chưa có mô tả bối cảnh)";
        try {
            ArrayNode arr = objectMapper.createArrayNode();

            ObjectNode missionIntro = objectMapper.createObjectNode();
            missionIntro.put("type", "MISSION_INTRO");
            missionIntro.put("japanese_content", "");
            missionIntro.put("translation",
                    "Bạn đóng vai " + resolvedLearner + "; AI đóng vai " + resolvedAi + ".");
            missionIntro.put("romaji", "");
            arr.add(missionIntro);

            ObjectNode sceneIntro = objectMapper.createObjectNode();
            sceneIntro.put("type", "SCENE_INTRO");
            sceneIntro.put("japanese_content", "");
            sceneIntro.put("translation", "Bối cảnh: " + resolvedScenario);
            sceneIntro.put("romaji", "");
            arr.add(sceneIntro);

            ObjectNode systemNotice = objectMapper.createObjectNode();
            systemNotice.put("type", "SYSTEM");
            systemNotice.put("japanese_content", "");
            systemNotice.put("translation",
                    "Hệ thống AI tạm thời không khả dụng. Vui lòng gửi tin nhắn đầu tiên — "
                            + "AI sẽ phản hồi khi hệ thống khôi phục.");
            systemNotice.put("romaji", "");
            arr.add(systemNotice);

            return objectMapper.writeValueAsString(arr);
        } catch (Exception e) {
            log.warn("Failed to build context-aware fallback opening: {}", e.getMessage());
            // Last-resort plain SYSTEM notice — still safer than the old shopkeeper hardcode.
            return "[{\"type\":\"SYSTEM\",\"japanese_content\":\"\","
                    + "\"translation\":\"Hệ thống AI tạm thời không khả dụng. Vui lòng thử lại sau.\","
                    + "\"romaji\":\"\"}]";
        }
    }

    private String defaultSetupResponse() {
        return "[{\"type\":\"DIALOGUE\",\"japanese_content\":\"どんな場面を練習したいですか？\",\"translation\":\"Bạn muốn luyện tập trong tình huống nào?\",\"romaji\":\"Donna bamen wo renshuu shitai desu ka?\",\"corrections\":[]}]";
    }
}
