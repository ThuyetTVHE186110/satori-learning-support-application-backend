package com.fu.pronunciation.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fu.pronunciation.config.JapanesePronunciationProperties;
import com.fu.pronunciation.dto.JapaneseAssessmentResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Service
@Slf4j
public class JapanesePronunciationProvider {

    private final JapanesePronunciationProperties properties;
    private final ObjectMapper objectMapper;
    private final RestTemplate healthRestTemplate;
    private final RestTemplate assessRestTemplate;

    // Cached health check: avoid HTTP call on every isAvailable() invocation
    private final AtomicBoolean cachedHealthy = new AtomicBoolean(false);
    private final AtomicLong lastHealthCheckTime = new AtomicLong(0);
    private static final long HEALTH_CACHE_TTL_MS = 30_000; // 30 seconds

    public JapanesePronunciationProvider(JapanesePronunciationProperties properties,
                                         ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;

        // Short timeout for health checks
        SimpleClientHttpRequestFactory healthFactory = new SimpleClientHttpRequestFactory();
        healthFactory.setConnectTimeout(Math.min(properties.getTimeout(), 5000));
        healthFactory.setReadTimeout(Math.min(properties.getTimeout(), 5000));
        this.healthRestTemplate = new RestTemplate(healthFactory);

        // Full timeout for assessment calls (reused across requests)
        SimpleClientHttpRequestFactory assessFactory = new SimpleClientHttpRequestFactory();
        assessFactory.setConnectTimeout(properties.getTimeout());
        assessFactory.setReadTimeout(properties.getTimeout());
        this.assessRestTemplate = new RestTemplate(assessFactory);
    }

    public JapaneseAssessmentResult assess(byte[] audioData, String audioFormat, String referenceText) {
        if (!properties.isEnabled()) {
            return JapaneseAssessmentResult.builder()
                    .success(false)
                    .errorMessage("Japanese pronunciation service is disabled")
                    .build();
        }

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);

            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();

            String filename = "audio." + (audioFormat != null ? audioFormat : "wav");
            ByteArrayResource audioResource = new ByteArrayResource(audioData) {
                @Override
                public String getFilename() {
                    return filename;
                }
            };
            body.add("audio", audioResource);
            body.add("reference_text", referenceText);

            HttpEntity<MultiValueMap<String, Object>> httpEntity = new HttpEntity<>(body, headers);

            String url = properties.getServiceUrl() + "/assess";
            ResponseEntity<String> response = assessRestTemplate.postForEntity(url, httpEntity, String.class);

            String responseBody = response.getBody();
            if (responseBody == null) {
                return JapaneseAssessmentResult.builder()
                        .success(false)
                        .errorMessage("Empty response from Japanese pronunciation service")
                        .build();
            }
            return objectMapper.readValue(responseBody, JapaneseAssessmentResult.class);

        } catch (Exception e) {
            log.error("Japanese pronunciation assessment failed: {}", e.getMessage());
            // Mark as unhealthy so we don't keep trying until next health check
            cachedHealthy.set(false);
            return JapaneseAssessmentResult.builder()
                    .success(false)
                    .errorMessage("Service call failed: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()))
                    .build();
        }
    }

    public boolean isAvailable() {
        if (!properties.isEnabled()) {
            return false;
        }

        long now = System.currentTimeMillis();
        long lastCheck = lastHealthCheckTime.get();

        // Return cached result if within TTL
        if (now - lastCheck < HEALTH_CACHE_TTL_MS) {
            return cachedHealthy.get();
        }

        // Perform actual health check
        try {
            String url = properties.getServiceUrl() + "/health";
            ResponseEntity<String> response = healthRestTemplate.getForEntity(url, String.class);
            boolean healthy = response.getStatusCode().is2xxSuccessful();
            cachedHealthy.set(healthy);
            lastHealthCheckTime.set(now);
            return healthy;
        } catch (Exception e) {
            log.debug("Japanese pronunciation service health check failed: {}", e.getMessage());
            cachedHealthy.set(false);
            lastHealthCheckTime.set(now);
            return false;
        }
    }
}
