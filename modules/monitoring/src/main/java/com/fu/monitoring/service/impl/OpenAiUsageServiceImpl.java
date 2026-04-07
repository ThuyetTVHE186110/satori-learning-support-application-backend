package com.fu.monitoring.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fu.common.exception.BusinessException;
import com.fu.monitoring.dto.OpenAiUsageResponse;
import com.fu.monitoring.service.OpenAiUsageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

@Service
@RequiredArgsConstructor
@Slf4j
public class OpenAiUsageServiceImpl implements OpenAiUsageService {

    private final WebClient openAiWebClient;

    @Value("${spring.ai.openai.api-key:}")
    private String apiKey;

    @Override
    public OpenAiUsageResponse getUsage() {
        if (apiKey == null || apiKey.isBlank()) {
            throw new BusinessException("OPENAI_API_KEY_MISSING", "OpenAI API key is not configured.");
        }

        try {
            JsonNode root = openAiWebClient.get()
                    .uri("/dashboard/billing/credit_grants")
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, response -> response.bodyToMono(String.class)
                            .flatMap(body -> Mono.error(new BusinessException("OPENAI_USAGE_CLIENT_ERROR", "OpenAI usage request failed: " + body))))
                    .onStatus(HttpStatusCode::is5xxServerError, response -> response.bodyToMono(String.class)
                            .flatMap(body -> Mono.error(new BusinessException("OPENAI_USAGE_SERVER_ERROR", "OpenAI service unavailable: " + body))))
                    .bodyToMono(JsonNode.class)
                    .block();

            if (root == null) {
                throw new BusinessException("OPENAI_USAGE_EMPTY", "OpenAI usage response is empty.");
            }

            double totalGranted = root.path("total_granted").asDouble(0.0);
            double totalUsed = root.path("total_used").asDouble(0.0);
            double totalAvailable = root.path("total_available").asDouble(0.0);

            long expiresAtEpoch = 0L;
            JsonNode grants = root.path("grants").path("data");
            if (grants.isArray()) {
                for (JsonNode grant : grants) {
                    expiresAtEpoch = Math.max(expiresAtEpoch, grant.path("expires_at").asLong(0L));
                }
            }
            Instant expiresAt = expiresAtEpoch > 0 ? Instant.ofEpochSecond(expiresAtEpoch) : null;

            return OpenAiUsageResponse.builder()
                    .totalGranted(totalGranted)
                    .totalUsed(totalUsed)
                    .totalAvailable(totalAvailable)
                    .expiresAt(expiresAt)
                    .currency("USD")
                    .build();
        } catch (BusinessException ex) {
            // If the endpoint refuses secret keys (session-key requirement), fallback to /v1/usage
            if (ex.getMessage() != null && ex.getMessage().contains("session key")) {
                log.warn("Falling back to /v1/usage because credit_grants requires a session key.");
                return fetchUsageTokens();
            }
            throw ex;
        }
    }

    private OpenAiUsageResponse fetchUsageTokens() {
        String start = LocalDate.now().minusDays(30).format(DateTimeFormatter.ISO_LOCAL_DATE);
        String end = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);

        try {
            JsonNode root = openAiWebClient.get()
                    .uri(builder -> builder.path("/v1/usage")
                            .queryParam("start_date", start)
                            .queryParam("end_date", end)
                            .build())
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, response -> response.bodyToMono(String.class)
                            .flatMap(body -> Mono.error(new BusinessException("OPENAI_USAGE_CLIENT_ERROR", "OpenAI usage request failed: " + body))))
                    .onStatus(HttpStatusCode::is5xxServerError, response -> response.bodyToMono(String.class)
                            .flatMap(body -> Mono.error(new BusinessException("OPENAI_USAGE_SERVER_ERROR", "OpenAI service unavailable: " + body))))
                    .bodyToMono(JsonNode.class)
                    .block();

            return mapUsageTokens(root);
        } catch (BusinessException ex) {
            if (ex.getMessage() != null && ex.getMessage().contains("Missing required parameter: 'date'")) {
                log.warn("/v1/usage requires single 'date' param on this account. Retrying with today's date.");
                return fetchUsageTokensSingleDate(LocalDate.now());
            }
            throw ex;
        }
    }

    private OpenAiUsageResponse fetchUsageTokensSingleDate(LocalDate date) {
        JsonNode root = openAiWebClient.get()
                .uri(builder -> builder.path("/v1/usage")
                        .queryParam("date", date.format(DateTimeFormatter.ISO_LOCAL_DATE))
                        .build())
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, response -> response.bodyToMono(String.class)
                        .flatMap(body -> Mono.error(new BusinessException("OPENAI_USAGE_CLIENT_ERROR", "OpenAI usage request failed: " + body))))
                .onStatus(HttpStatusCode::is5xxServerError, response -> response.bodyToMono(String.class)
                        .flatMap(body -> Mono.error(new BusinessException("OPENAI_USAGE_SERVER_ERROR", "OpenAI service unavailable: " + body))))
                .bodyToMono(JsonNode.class)
                .block();

        return mapUsageTokens(root);
    }

    private OpenAiUsageResponse mapUsageTokens(JsonNode root) {
        if (root == null || !root.path("data").isArray()) {
            throw new BusinessException("OPENAI_USAGE_EMPTY", "OpenAI usage response is empty.");
        }

        long inputTokens = 0;
        long outputTokens = 0;
        for (JsonNode item : root.path("data")) {
            JsonNode tokens = item.path("tokens");
            inputTokens += tokens.path("input").asLong(0L);
            outputTokens += tokens.path("output").asLong(0L);
        }
        double totalUsedTokens = inputTokens + outputTokens;

        return OpenAiUsageResponse.builder()
                .totalGranted(0.0)
                .totalUsed(totalUsedTokens)
                .totalAvailable(0.0)
                .expiresAt(null)
                .currency(null)
                .build();
    }
}
