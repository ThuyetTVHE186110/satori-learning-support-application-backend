package com.fu.pronunciation.provider;

import com.fu.pronunciation.dto.AssessmentRequest;
import com.fu.pronunciation.dto.AssessmentResult;
import com.fu.pronunciation.dto.TranscriptionResult;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Composite provider: uses Azure Speech as primary, falls back to SpeechSuper.
 * Marked @Primary so Spring injects this wherever PronunciationAssessmentProvider is needed.
 */
@Service
@Primary
@Slf4j
public class CompositePronunciationProvider implements PronunciationAssessmentProvider {

    private final AzureSpeechProvider azureSpeechProvider;
    private final SpeechSuperProvider speechSuperProvider;
    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    public CompositePronunciationProvider(AzureSpeechProvider azureSpeechProvider,
                                          SpeechSuperProvider speechSuperProvider) {
        this.azureSpeechProvider = azureSpeechProvider;
        this.speechSuperProvider = speechSuperProvider;
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
        if (azureSpeechProvider.isAvailable()) {
            log.debug("Using Azure Speech as primary pronunciation provider");
            CompletableFuture<AssessmentResult> future = CompletableFuture
                    .supplyAsync(() -> azureSpeechProvider.assess(request), executor)
                    .exceptionally(ex -> {
                        log.error("Azure Speech assessment failed: {}", ex.getMessage());
                        if (speechSuperProvider.isAvailable()) {
                            log.info("Falling back to SpeechSuper after Azure failure");
                            return speechSuperProvider.assess(request);
                        }
                        return AssessmentResult.failure("Azure failed: " + ex.getMessage());
                    });
            return future.join();
        }

        if (speechSuperProvider.isAvailable()) {
            log.debug("Using SpeechSuper as primary pronunciation provider");
            CompletableFuture<AssessmentResult> future = CompletableFuture
                    .supplyAsync(() -> speechSuperProvider.assess(request), executor)
                    .exceptionally(ex -> {
                        log.error("SpeechSuper assessment failed: {}", ex.getMessage());
                        return AssessmentResult.failure("SpeechSuper failed: " + ex.getMessage());
                    });
            return future.join();
        }

        return AssessmentResult.failure("No pronunciation provider available");
    }

    @Override
    public boolean isAvailable() {
        return azureSpeechProvider.isAvailable() || speechSuperProvider.isAvailable();
    }

    @Override
    public TranscriptionResult recognize(byte[] audioData, String audioFormat) {
        if (azureSpeechProvider.isAvailable()) {
            return azureSpeechProvider.recognize(audioData, audioFormat);
        }
        return TranscriptionResult.failure("No STT-capable provider available");
    }
}
