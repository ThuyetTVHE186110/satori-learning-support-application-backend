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
 * Composite provider that calls both SpeechSuper (segmental scoring) and
 * the custom Japanese pronunciation service (pitch accent + mora timing)
 * in parallel. Marked @Primary so Spring injects this wherever
 * PronunciationAssessmentProvider is needed.
 */
@Service
@Primary
@Slf4j
public class CompositePronunciationProvider implements PronunciationAssessmentProvider {

    private final SpeechSuperProvider speechSuperProvider;
    private final JapanesePronunciationProvider japanesePronunciationProvider;
    private final JapanesePronunciationProperties japaneseProperties;
    private final ObjectMapper objectMapper;
    private final ExecutorService executor = Executors.newFixedThreadPool(4);

    public CompositePronunciationProvider(SpeechSuperProvider speechSuperProvider,
                                          JapanesePronunciationProvider japanesePronunciationProvider,
                                          JapanesePronunciationProperties japaneseProperties,
                                          ObjectMapper objectMapper) {
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
        // Task A: SpeechSuper (segmental scores)
        CompletableFuture<AssessmentResult> speechSuperFuture = CompletableFuture
                .supplyAsync(() -> speechSuperProvider.assess(request), executor)
                .exceptionally(ex -> {
                    log.error("SpeechSuper assessment failed: {}", ex.getMessage());
                    return AssessmentResult.failure("SpeechSuper failed: " + ex.getMessage());
                });

        // Task B: Japanese pronunciation (pitch accent + mora timing)
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
        CompletableFuture.allOf(speechSuperFuture, japaneseFuture).join();

        AssessmentResult speechSuperResult = speechSuperFuture.join();
        JapaneseAssessmentResult japaneseResult = japaneseFuture.join();

        // Merge results
        return mergeResults(speechSuperResult, japaneseResult);
    }

    @Override
    public boolean isAvailable() {
        return speechSuperProvider.isAvailable();
    }

    private AssessmentResult mergeResults(AssessmentResult speechSuper,
                                           JapaneseAssessmentResult japanese) {
        if (speechSuper == null || !speechSuper.isSuccess()) {
            // If SpeechSuper failed, still return what we can
            if (japanese != null && japanese.isSuccess()) {
                return AssessmentResult.builder()
                        .success(true)
                        .pitchAccentScore(japanese.getPitchAccentScore())
                        .moraTimingScore(japanese.getMoraTimingScore())
                        .detailedPronunciationFeedback(serializeFeedback(japanese))
                        .build();
            }
            return speechSuper != null ? speechSuper : AssessmentResult.failure("Both providers failed");
        }

        // SpeechSuper succeeded; add Japanese scores if available
        if (japanese != null && japanese.isSuccess()) {
            return AssessmentResult.builder()
                    .success(speechSuper.isSuccess())
                    .overallScore(speechSuper.getOverallScore())
                    .accuracyScore(speechSuper.getAccuracyScore())
                    .fluencyScore(speechSuper.getFluencyScore())
                    .prosodyScore(speechSuper.getProsodyScore())
                    .transcribedText(speechSuper.getTranscribedText())
                    .rawResponse(speechSuper.getRawResponse())
                    .pitchAccentScore(japanese.getPitchAccentScore())
                    .moraTimingScore(japanese.getMoraTimingScore())
                    .detailedPronunciationFeedback(serializeFeedback(japanese))
                    .build();
        }

        return speechSuper;
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
