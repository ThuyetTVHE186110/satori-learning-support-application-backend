package com.fu.infrastructure.config;

import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.util.Timeout;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;

/**
 * Configures read/connect timeouts on the RestClient used by Spring AI's OpenAI integration.
 * <p>
 * Default Apache HttpClient 5 timeouts are too short for long-running LLM calls
 * (SelfReflectionAgent, GroundingVerifier, etc.) that can take 30–90 seconds.
 */
@Configuration
public class OpenAiHttpConfig {

    /** Connection-request timeout: how long to wait to borrow a connection from the pool. */
    private static final int CONNECTION_REQUEST_TIMEOUT_SECONDS = 30;

    /** Response timeout (read timeout): how long to wait for the server to return data. */
    private static final int RESPONSE_TIMEOUT_SECONDS = 120;

    @Bean
    public RestClientCustomizer openAiTimeoutCustomizer() {
        return builder -> {
            RequestConfig requestConfig = RequestConfig.custom()
                    .setConnectionRequestTimeout(Timeout.ofSeconds(CONNECTION_REQUEST_TIMEOUT_SECONDS))
                    .setResponseTimeout(Timeout.ofSeconds(RESPONSE_TIMEOUT_SECONDS))
                    .build();

            HttpClient httpClient = HttpClients.custom()
                    .setDefaultRequestConfig(requestConfig)
                    .build();

            builder.requestFactory(new HttpComponentsClientHttpRequestFactory(httpClient));
        };
    }
}
