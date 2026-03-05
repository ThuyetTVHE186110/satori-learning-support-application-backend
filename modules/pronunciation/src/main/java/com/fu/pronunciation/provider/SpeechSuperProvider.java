package com.fu.pronunciation.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fu.pronunciation.config.SpeechSuperProperties;
import com.fu.pronunciation.dto.AssessmentRequest;
import com.fu.pronunciation.dto.AssessmentResult;
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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.HexFormat;
import java.util.UUID;

@Service
@Slf4j
public class SpeechSuperProvider implements PronunciationAssessmentProvider {

    private final SpeechSuperProperties properties;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    public SpeechSuperProvider(SpeechSuperProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.getTimeout());
        factory.setReadTimeout(properties.getTimeout());
        this.restTemplate = new RestTemplate(factory);
    }

    @Override
    public AssessmentResult assess(AssessmentRequest request) {
        if (!isAvailable()) {
            return AssessmentResult.failure("SpeechSuper is not configured");
        }

        try {
            long timestamp = System.currentTimeMillis() / 1000;
            String connectSig = sha1Hex(properties.getAppKey() + timestamp + properties.getSecretKey());
            String startSig = sha1Hex(properties.getAppKey() + timestamp + properties.getSecretKey());

            var connectParam = new LinkedHashMap<String, Object>();
            connectParam.put("cmd", "connect");
            var connectInner = new LinkedHashMap<String, Object>();
            var sdkMap = new LinkedHashMap<String, Object>();
            sdkMap.put("version", 16777472);
            sdkMap.put("source", 9);
            sdkMap.put("protocol", 2);
            connectInner.put("sdk", sdkMap);
            var connectApp = new LinkedHashMap<String, Object>();
            connectApp.put("applicationId", properties.getAppKey());
            connectApp.put("sig", connectSig);
            connectApp.put("timestamp", String.valueOf(timestamp));
            connectInner.put("app", connectApp);
            connectParam.put("param", connectInner);

            var startParam = new LinkedHashMap<String, Object>();
            startParam.put("cmd", "start");
            var startInner = new LinkedHashMap<String, Object>();
            var startApp = new LinkedHashMap<String, Object>();
            startApp.put("applicationId", properties.getAppKey());
            startApp.put("sig", startSig);
            startApp.put("timestamp", String.valueOf(timestamp));
            startInner.put("app", startApp);
            var audioMap = new LinkedHashMap<String, Object>();
            audioMap.put("audioType", request.getAudioFormat() != null ? request.getAudioFormat() : "wav");
            audioMap.put("sampleRate", 16000);
            audioMap.put("channel", 1);
            audioMap.put("sampleBytes", 2);
            startInner.put("audio", audioMap);
            var requestMap = new LinkedHashMap<String, Object>();
            requestMap.put("coreType", request.getCoreType());
            requestMap.put("refText", request.getReferenceText());
            requestMap.put("tokenId", UUID.randomUUID().toString());
            startInner.put("request", requestMap);
            startParam.put("param", startInner);

            var paramsRoot = new LinkedHashMap<String, Object>();
            paramsRoot.put("connect", connectParam);
            paramsRoot.put("start", startParam);

            String paramsJson = objectMapper.writeValueAsString(paramsRoot);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);

            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("text", paramsJson);

            String audioFilename = "audio." + (request.getAudioFormat() != null ? request.getAudioFormat() : "wav");
            ByteArrayResource audioResource = new ByteArrayResource(request.getAudioData()) {
                @Override
                public String getFilename() {
                    return audioFilename;
                }
            };
            body.add("audio", audioResource);

            HttpEntity<MultiValueMap<String, Object>> httpEntity = new HttpEntity<>(body, headers);

            String url = properties.getBaseUrl() + "/" + request.getCoreType();
            ResponseEntity<String> response = restTemplate.postForEntity(url, httpEntity, String.class);

            return parseResponse(response.getBody());

        } catch (Exception e) {
            log.error("SpeechSuper assessment failed: {}", e.getMessage(), e);
            return AssessmentResult.failure("Assessment failed: " + e.getMessage());
        }
    }

    @Override
    public boolean isAvailable() {
        return properties.getAppKey() != null && !properties.getAppKey().isBlank()
                && properties.getSecretKey() != null && !properties.getSecretKey().isBlank();
    }

    private AssessmentResult parseResponse(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            int retCode = root.path("retCode").asInt(-1);

            if (retCode != 0) {
                String errMsg = root.path("retMsg").asText("Unknown error");
                return AssessmentResult.failure("SpeechSuper error: " + errMsg);
            }

            JsonNode result = root.path("result");
            double overall = result.path("overall").asDouble(0);
            double accuracy = result.path("accuracy").asDouble(0);
            double fluency = result.path("fluency").path("overall").asDouble(0);
            double prosody = result.path("prosody").path("overall").asDouble(0);
            String transcribed = result.path("transcription").asText("");

            return AssessmentResult.builder()
                    .success(true)
                    .overallScore(overall)
                    .accuracyScore(accuracy)
                    .fluencyScore(fluency)
                    .prosodyScore(prosody)
                    .transcribedText(transcribed)
                    .rawResponse(responseBody)
                    .build();

        } catch (Exception e) {
            log.error("Failed to parse SpeechSuper response: {}", e.getMessage());
            return AssessmentResult.failure("Failed to parse response: " + e.getMessage());
        }
    }

    private String sha1Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-1 not available", e);
        }
    }
}
