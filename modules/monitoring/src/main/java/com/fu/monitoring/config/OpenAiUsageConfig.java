package com.fu.monitoring.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class OpenAiUsageConfig {

    @Value("${spring.ai.openai.api-key:}")
    private String apiKey;

    @Bean
    public WebClient openAiWebClient(WebClient.Builder builder) {
        WebClient.Builder configured = builder.baseUrl("https://api.openai.com");
        if (apiKey != null && !apiKey.isBlank()) {
            configured.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
        }
        return configured.build();
    }
}

