package com.fu.roleplay.ai;

import com.fu.common.ai.PromptUtils;
import com.fu.infrastructure.learner.LearningInsightPoint;
import com.fu.infrastructure.learner.SkillScore;
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

    private static final String ROLEPLAY_MODEL = "gpt-4.1-mini";
    private static final String REDIS_KEY_PREFIX = "roleplay:history:";
    private static final Duration HISTORY_TTL = Duration.ofHours(2);
    private static final int MAX_CONTEXT_MESSAGES = 20; // sliding window: last 20 messages

    private final ChatClient chatClient;
    private final RedisTemplate<String, Object> redisTemplate;
    private final RoleplayPromptBuilder promptBuilder;

    public RoleplayAIService(OpenAiChatModel chatModel,
                              RedisTemplate<String, Object> redisTemplate,
                              RoleplayPromptBuilder promptBuilder) {
        this.chatClient = ChatClient.builder(chatModel).build();
        this.redisTemplate = redisTemplate;
        this.promptBuilder = promptBuilder;
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
        try {
            List<ConversationMessage> recentHistory = PromptUtils.slidingTail(history, MAX_CONTEXT_MESSAGES);

            StringBuilder conversationHistory = new StringBuilder();
            conversationHistory.append("<conversation_history>\n");
            for (ConversationMessage msg : recentHistory) {
                String role = msg.getRole() == ConversationMessage.MessageRole.USER ? "Learner" : "AI";
                conversationHistory.append(role).append(": ").append(sanitizeUserInput(msg.getContent())).append("\n");
            }
            conversationHistory.append("Learner: ").append(sanitizeUserInput(userMessage)).append("\n");
            conversationHistory.append("</conversation_history>\n");

            String rawResponse = chatClient.prompt()
                    .options(OpenAiChatOptions.builder().model(ROLEPLAY_MODEL).build())
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

    public String generateOpeningMessage(String systemPrompt) {
        try {
            String rawResponse = chatClient.prompt()
                    .options(OpenAiChatOptions.builder().model(ROLEPLAY_MODEL).build())
                    .system(systemPrompt)
                    .user("Start the roleplay conversation. Return the opening JSON array with MISSION_INTRO, SCENE_INTRO, and first DIALOGUE segments as specified in your instructions.")
                    .call()
                    .content();

            return cleanJson(rawResponse);
        } catch (NonTransientAiException e) {
            log.warn("AI service unavailable for opening message (quota/billing): {}", e.getMessage());
            return defaultOpeningArrayResponse();
        } catch (Exception e) {
            log.error("Failed to generate opening message: {}", e.getMessage());
            return defaultOpeningArrayResponse();
        }
    }

    public String generateSetupOpening(String setupSystemPrompt) {
        try {
            String rawResponse = chatClient.prompt()
                    .options(OpenAiChatOptions.builder().model(ROLEPLAY_MODEL).build())
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
            redisTemplate.opsForValue().set(key, systemPrompt, HISTORY_TTL);
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
            List<ConversationMessage> recentHistory = PromptUtils.slidingTail(history, MAX_CONTEXT_MESSAGES);

            StringBuilder sb = new StringBuilder();
            sb.append("<conversation_history>\n");
            for (ConversationMessage msg : recentHistory) {
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
                    .options(OpenAiChatOptions.builder().model(ROLEPLAY_MODEL).build())
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

    private String defaultOpeningArrayResponse() {
        return "[" +
               "{\"type\":\"MISSION_INTRO\",\"japanese_content\":\"本日はよろしくお願いします。会話の練習を始めましょう。\",\"translation\":\"Hôm nay rất vui được luyện tập cùng bạn. Hãy bắt đầu buổi luyện tập hội thoại.\",\"romaji\":\"Honjitsu wa yoroshiku onegai shimasu. Kaiwa no renshuu wo hajimemashou.\"}," +
               "{\"type\":\"SCENE_INTRO\",\"japanese_content\":\"会話の練習を始めましょう。\",\"translation\":\"Hãy bắt đầu buổi luyện tập hội thoại.\",\"romaji\":\"Kaiwa no renshu wo hajimemasho.\"}," +
               "{\"type\":\"DIALOGUE\",\"japanese_content\":\"いらっしゃいませ。本日はどのようなご用件でしょうか？\",\"translation\":\"Xin chào, hôm nay tôi có thể giúp gì cho bạn?\",\"romaji\":\"Irasshaimase. Honjitsu wa dono you na goyo-ken deshou ka?\",\"corrections\":[]}" +
               "]";
    }

    private String defaultSetupResponse() {
        return "[{\"type\":\"DIALOGUE\",\"japanese_content\":\"どんな場面を練習したいですか？\",\"translation\":\"Bạn muốn luyện tập trong tình huống nào?\",\"romaji\":\"Donna bamen wo renshuu shitai desu ka?\",\"corrections\":[]}]";
    }
}
