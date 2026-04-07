package com.fu.infrastructure.health;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Health indicator for the OpenAI API connection.
 *
 * Reports DOWN if the API key is not configured.
 * Does NOT make a live API call on every check to avoid cost and latency.
 *
 * Accessible at:
 *   /actuator/health        (public — shows overall UP/DOWN only)
 *   /actuator/health/openAi (requires ADMIN — shows details)
 */
@Slf4j
@Component("openAi")
public class OpenAiHealthIndicator implements HealthIndicator {

    @Value("${spring.ai.openai.api-key:}")
    private String apiKey;

    private final OpenAiChatModel chatModel;

    public OpenAiHealthIndicator(OpenAiChatModel chatModel) {
        this.chatModel = chatModel;
    }

    @Override
    public Health health() {
        if (apiKey == null || apiKey.isBlank()) {
            return Health.down()
                    .withDetail("reason", "OPENAI_API_KEY is not configured")
                    .build();
        }

        if (chatModel == null) {
            return Health.down()
                    .withDetail("reason", "OpenAiChatModel bean not available")
                    .build();
        }

        // Redact all but last 4 chars for auditability without leaking the key
        String redacted = apiKey.length() > 8
                ? "***" + apiKey.substring(apiKey.length() - 4)
                : "***";

        return Health.up()
                .withDetail("model", chatModel.getDefaultOptions().getModel())
                .withDetail("keyConfigured", redacted)
                .build();
    }
}
