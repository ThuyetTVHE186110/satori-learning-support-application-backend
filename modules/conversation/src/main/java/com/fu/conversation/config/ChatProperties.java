package com.fu.conversation.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration for the CURRICULUM_QA chatbot runtime behaviour.
 *
 * Properties under {@code conversation.chat.*} in application.yml.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "conversation.chat")
public class ChatProperties {

    /** OpenAI model for the main chat response. */
    private String model = "gpt-4o-mini";

    /** Sampling temperature for the main chat call (0.0 = deterministic, 1.0 = creative). */
    private double temperature = 0.3;

    /** Max tokens the model may generate per response. */
    private int maxResponseTokens = 1024;

    /**
     * Token budget reserved for conversation history.
     * History is trimmed newest → oldest until it fits within this budget,
     * leaving room for the system prompt, RAG context, and the new question.
     */
    private int maxHistoryTokens = 2000;

    /** Max chat messages a user may send per rate-limit window. */
    private int rateLimitMaxRequests = 20;

    /** Duration of the rate-limit sliding window, in minutes. */
    private int rateLimitWindowMinutes = 1;

    /**
     * ACTIVE sessions older than this many days are marked ABANDONED
     * by the nightly cleanup scheduler.
     */
    private int sessionCleanupRetentionDays = 30;
}
