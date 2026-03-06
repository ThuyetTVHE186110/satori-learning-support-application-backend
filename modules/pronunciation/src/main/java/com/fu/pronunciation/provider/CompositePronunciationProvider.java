package com.fu.pronunciation.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fu.pronunciation.config.JapanesePronunciationProperties;
import com.fu.pronunciation.dto.AssessmentRequest;
import com.fu.pronunciation.dto.AssessmentResult;
import com.fu.pronunciation.dto.JapaneseAssessmentResult;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Composite provider that selects the best available primary provider
 * (Azure preferred, SpeechSuper fallback) and optionally runs
 * the custom Japanese pronunciation service (pitch accent + mora timing)
 * in parallel. Marked @Primary so Spring injects this wherever
 * PronunciationAssessmentProvider is needed.
 */
@Service
@Primary
@Slf4j
public class CompositePronunciationProvider implements PronunciationAssessmentProvider {

    private final AzureSpeechProvider azureSpeechProvider;
    private final SpeechSuperProvider speechSuperProvider;
    private final JapanesePronunciationProvider japanesePronunciationProvider;
    private final JapanesePronunciationProperties japaneseProperties;
    private final ObjectMapper objectMapper;
    private final ExecutorService executor = Executors.newFixedThreadPool(4);

    public CompositePronunciationProvider(AzureSpeechProvider azureSpeechProvider,
                                          SpeechSuperProvider speechSuperProvider,
                                          JapanesePronunciationProvider japanesePronunciationProvider,
                                          JapanesePronunciationProperties japaneseProperties,
                                          ObjectMapper objectMapper) {
        this.azureSpeechProvider = azureSpeechProvider;
        this.speechSuperProvider = speechSuperProvider;
        this.japanesePronunciationProvider = japanesePronunciationProvider;
        this.japaneseProperties = japaneseProperties;
        this.objectMapper = objectMapper;
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public AssessmentResult assess(AssessmentRequest request) {
        // Task A: Primary provider (Azure preferred, SpeechSuper fallback)
        CompletableFuture<AssessmentResult> primaryFuture;
        if (azureSpeechProvider.isAvailable()) {
            log.debug("Using Azure Speech as primary pronunciation provider");
            primaryFuture = CompletableFuture
                    .supplyAsync(() -> azureSpeechProvider.assess(request), executor)
                    .exceptionally(ex -> {
                        log.error("Azure Speech assessment failed: {}", ex.getMessage());
                        // Fallback to SpeechSuper if Azure fails and SpeechSuper is available
                        if (speechSuperProvider.isAvailable()) {
                            log.info("Falling back to SpeechSuper after Azure failure");
                            return speechSuperProvider.assess(request);
                        }
                        return AssessmentResult.failure("Azure failed: " + ex.getMessage());
                    });
        } else if (speechSuperProvider.isAvailable()) {
            log.debug("Using SpeechSuper as primary pronunciation provider");
            primaryFuture = CompletableFuture
                    .supplyAsync(() -> speechSuperProvider.assess(request), executor)
                    .exceptionally(ex -> {
                        log.error("SpeechSuper assessment failed: {}", ex.getMessage());
                        return AssessmentResult.failure("SpeechSuper failed: " + ex.getMessage());
                    });
        } else {
            primaryFuture = CompletableFuture.completedFuture(
                    AssessmentResult.failure("No pronunciation provider available"));
        }

        // Task B: Japanese pronunciation (pitch accent + mora timing) — parallel
        CompletableFuture<JapaneseAssessmentResult> japaneseFuture;
        if (japaneseProperties.isEnabled() && japanesePronunciationProvider.isAvailable()) {
            japaneseFuture = CompletableFuture
                    .supplyAsync(() -> japanesePronunciationProvider.assess(
                            request.getAudioData(),
                            request.getAudioFormat(),
                            request.getReferenceText()
                    ), executor)
                    .exceptionally(ex -> {
                        log.error("Japanese pronunciation assessment failed: {}", ex.getMessage());
                        return JapaneseAssessmentResult.builder()
                                .success(false)
                                .errorMessage(ex.getMessage())
                                .build();
                    });
        } else {
            japaneseFuture = CompletableFuture.completedFuture(null);
        }

        // Wait for both
        CompletableFuture.allOf(primaryFuture, japaneseFuture).join();

        AssessmentResult primaryResult = primaryFuture.join();
        JapaneseAssessmentResult japaneseResult = japaneseFuture.join();

        // Merge results
        return mergeResults(primaryResult, japaneseResult);
    }

    @Override
    public boolean isAvailable() {
        return azureSpeechProvider.isAvailable() || speechSuperProvider.isAvailable();
    }

    private AssessmentResult mergeResults(AssessmentResult primary,
                                           JapaneseAssessmentResult japanese) {
        if (primary == null || !primary.isSuccess()) {
            // If primary provider failed, still return what we can
            if (japanese != null && japanese.isSuccess()) {
                return AssessmentResult.builder()
                        .success(true)
                        .pitchAccentScore(japanese.getPitchAccentScore())
                        .moraTimingScore(japanese.getMoraTimingScore())
                        .detailedPronunciationFeedback(serializeFeedback(japanese))
                        .build();
            }
            return primary != null ? primary : AssessmentResult.failure("All providers failed");
        }

        // Primary succeeded; add Japanese scores if available
        if (japanese != null && japanese.isSuccess()) {
            return AssessmentResult.builder()
                    .success(true)
                    .overallScore(primary.getOverallScore())
                    .accuracyScore(primary.getAccuracyScore())
                    .fluencyScore(primary.getFluencyScore())
                    .prosodyScore(primary.getProsodyScore())
                    .completenessScore(primary.getCompletenessScore())
                    .transcribedText(primary.getTranscribedText())
                    .rawResponse(primary.getRawResponse())
                    .providerName(primary.getProviderName())
                    .wordDetails(primary.getWordDetails())
                    .pitchAccentScore(japanese.getPitchAccentScore())
                    .moraTimingScore(japanese.getMoraTimingScore())
                    .detailedPronunciationFeedback(serializeFeedback(japanese))
                    .build();
        }

        return primary;
    }

    private String serializeFeedback(JapaneseAssessmentResult result) {
        if (result.getDetailedFeedback() == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(result.getDetailedFeedback());
        } catch (Exception e) {
            log.warn("Failed to serialize Japanese pronunciation feedback: {}", e.getMessage());
            return null;
        }
    }
}
