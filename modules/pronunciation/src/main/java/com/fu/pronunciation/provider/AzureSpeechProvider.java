package com.fu.pronunciation.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fu.pronunciation.config.AzureSpeechProperties;
import com.fu.pronunciation.dto.AssessmentRequest;
import com.fu.pronunciation.dto.AssessmentResult;
import com.fu.pronunciation.dto.TranscriptionResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import com.microsoft.cognitiveservices.speech.*;
import com.microsoft.cognitiveservices.speech.audio.*;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
@Slf4j
public class AzureSpeechProvider implements PronunciationAssessmentProvider {

    private final AzureSpeechProperties properties;
    private final ObjectMapper objectMapper;

    public AzureSpeechProvider(AzureSpeechProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public AssessmentResult assess(AssessmentRequest request) {
        if (!isAvailable()) {
            return AssessmentResult.failure("Azure Speech is not configured");
        }

        SpeechConfig speechConfig = null;
        AudioConfig audioConfig = null;
        SpeechRecognizer recognizer = null;
        Path tempWavFile = null;

        try {
            // 1. Create SpeechConfig
            speechConfig = SpeechConfig.fromSubscription(
                    properties.getSubscriptionKey(), properties.getRegion());
            speechConfig.setSpeechRecognitionLanguage(properties.getLanguage());

            // 2. Create PronunciationAssessmentConfig.
            // Unscripted mode: empty referenceText + miscue disabled → Azure scores phonemes
            // against its own recognized words. Use this for free-form roleplay where there is
            // no prescribed utterance (passing the learner's own transcript as reference produces
            // tautologically high scores — audio matches its own recognition).
            PronunciationAssessmentGranularity granularity = mapGranularity(properties.getGranularity());
            String refText = request.isUnscripted() ? "" :
                    (request.getReferenceText() == null ? "" : request.getReferenceText());
            boolean enableMiscue = !request.isUnscripted();
            PronunciationAssessmentConfig pronConfig = new PronunciationAssessmentConfig(
                    refText,
                    PronunciationAssessmentGradingSystem.HundredMark,
                    granularity,
                    enableMiscue
            );
            pronConfig.enableProsodyAssessment();

            // 3. Build AudioConfig. For WAV, write to a temp file so the SDK parses sample
            // rate / channels / bits directly from the RIFF header. Pushing WAV bytes into a
            // PushStream declared as fixed 16 kHz PCM caused client recordings at other rates
            // (44.1/48 kHz) to be read as misaligned samples → SNR 0 → silent to Azure.
            String fmt = request.getAudioFormat() != null ? request.getAudioFormat().toLowerCase() : "";
            if ("mp3".equals(fmt)) {
                PushAudioInputStream pushStream = AudioInputStream.createPushStream(
                        AudioStreamFormat.getCompressedFormat(AudioStreamContainerFormat.MP3));
                pushStream.write(request.getAudioData());
                audioConfig = AudioConfig.fromStreamInput(pushStream);
                pushStream.close();
            } else {
                byte[] wavBytes = ensureWavFormat(request.getAudioData(), request.getAudioFormat());
                tempWavFile = Files.createTempFile("azure-assess-", ".wav");
                Files.write(tempWavFile, wavBytes);
                audioConfig = AudioConfig.fromWavFileInput(tempWavFile.toString());
            }

            // 4. Create recognizer and apply pronunciation assessment
            recognizer = new SpeechRecognizer(speechConfig, audioConfig);
            pronConfig.applyTo(recognizer);

            // 5. Recognize
            long timeoutMs = properties.getTimeout();
            Future<SpeechRecognitionResult> recognitionFuture = recognizer.recognizeOnceAsync();
            SpeechRecognitionResult result = recognitionFuture.get(timeoutMs, TimeUnit.MILLISECONDS);

            // 6. Check result
            if (result.getReason() == ResultReason.RecognizedSpeech) {
                return parseRecognitionResult(result);
            } else if (result.getReason() == ResultReason.NoMatch) {
                return AssessmentResult.failure("No speech recognized in audio");
            } else {
                CancellationDetails cancellation = CancellationDetails.fromResult(result);
                return AssessmentResult.failure("Recognition cancelled: " + cancellation.getErrorDetails());
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return AssessmentResult.failure("Recognition interrupted");
        } catch (ExecutionException e) {
            log.error("Azure Speech recognition failed: {}", e.getCause().getMessage());
            return AssessmentResult.failure("Recognition failed: " + e.getCause().getMessage());
        } catch (TimeoutException e) {
            log.error("Azure Speech recognition timed out after {}ms", properties.getTimeout());
            // Cannot close the recognizer while async recognition is still running.
            // Schedule deferred cleanup so resources are freed once the native call finishes.
            scheduleDeferredCleanup(recognizer, audioConfig, speechConfig, tempWavFile);
            recognizer = null;
            audioConfig = null;
            speechConfig = null;
            tempWavFile = null;
            return AssessmentResult.failure("Recognition timed out");
        } catch (Exception e) {
            log.error("Azure Speech assessment error: {}", e.getMessage(), e);
            return AssessmentResult.failure("Azure assessment failed: " + e.getMessage());
        } finally {
            if (recognizer != null) recognizer.close();
            if (audioConfig != null) audioConfig.close();
            if (speechConfig != null) speechConfig.close();
            if (tempWavFile != null) {
                try { Files.deleteIfExists(tempWavFile); } catch (IOException ignored) {}
            }
        }
    }

    @Override
    public boolean isAvailable() {
        return properties.isEnabled()
                && properties.getSubscriptionKey() != null
                && !properties.getSubscriptionKey().isBlank();
    }

    /**
     * Dedicated speech-to-text — no pronunciation assessment overhead.
     * Used as the authoritative transcript for roleplay messages, replacing device STT.
     */
    @Override
    public TranscriptionResult recognize(byte[] audioData, String audioFormat) {
        if (!isAvailable()) {
            return TranscriptionResult.failure("Azure Speech is not configured");
        }
        if (audioData == null || audioData.length == 0) {
            return TranscriptionResult.failure("Audio data is empty");
        }

        SpeechConfig speechConfig = null;
        AudioConfig audioConfig = null;
        SpeechRecognizer recognizer = null;
        Path tempWavFile = null;

        try {
            speechConfig = SpeechConfig.fromSubscription(
                    properties.getSubscriptionKey(), properties.getRegion());
            speechConfig.setSpeechRecognitionLanguage(properties.getLanguage());

            // For WAV, write to temp file so SDK parses format from header (see assess() note).
            String fmt = audioFormat != null ? audioFormat.toLowerCase() : "";
            if ("mp3".equals(fmt)) {
                PushAudioInputStream pushStream = AudioInputStream.createPushStream(
                        AudioStreamFormat.getCompressedFormat(AudioStreamContainerFormat.MP3));
                pushStream.write(audioData);
                audioConfig = AudioConfig.fromStreamInput(pushStream);
                pushStream.close();
            } else {
                byte[] wavBytes = ensureWavFormat(audioData, audioFormat);
                tempWavFile = Files.createTempFile("azure-stt-", ".wav");
                Files.write(tempWavFile, wavBytes);
                audioConfig = AudioConfig.fromWavFileInput(tempWavFile.toString());
            }

            recognizer = new SpeechRecognizer(speechConfig, audioConfig);

            long timeoutMs = properties.getTimeout();
            Future<SpeechRecognitionResult> recognitionFuture = recognizer.recognizeOnceAsync();
            SpeechRecognitionResult result = recognitionFuture.get(timeoutMs, TimeUnit.MILLISECONDS);

            if (result.getReason() == ResultReason.RecognizedSpeech) {
                return TranscriptionResult.of(result.getText(), "AZURE");
            } else if (result.getReason() == ResultReason.NoMatch) {
                return TranscriptionResult.failure("No speech recognized");
            } else {
                CancellationDetails cancellation = CancellationDetails.fromResult(result);
                return TranscriptionResult.failure("Recognition cancelled: " + cancellation.getErrorDetails());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return TranscriptionResult.failure("Recognition interrupted");
        } catch (TimeoutException e) {
            log.warn("Azure STT timed out after {}ms", properties.getTimeout());
            scheduleDeferredCleanup(recognizer, audioConfig, speechConfig, tempWavFile);
            recognizer = null;
            audioConfig = null;
            speechConfig = null;
            tempWavFile = null;
            return TranscriptionResult.failure("Recognition timed out");
        } catch (Exception e) {
            log.error("Azure STT failed: {}", e.getMessage(), e);
            return TranscriptionResult.failure("STT failed: " + e.getMessage());
        } finally {
            if (recognizer != null) recognizer.close();
            if (audioConfig != null) audioConfig.close();
            if (speechConfig != null) speechConfig.close();
            if (tempWavFile != null) {
                try { Files.deleteIfExists(tempWavFile); } catch (IOException ignored) {}
            }
        }
    }

    private AssessmentResult parseRecognitionResult(SpeechRecognitionResult result) {
        try {
            // Get overall scores from PronunciationAssessmentResult
            PronunciationAssessmentResult pronResult =
                    PronunciationAssessmentResult.fromResult(result);
            if (pronResult == null) {
                log.warn("Azure returned null PronunciationAssessmentResult — fallback response");
                return AssessmentResult.builder()
                        .success(true)
                        .transcribedText(result.getText())
                        .rawResponse("{\"error\":\"no_assessment_result\"}")
                        .providerName("AZURE")
                        .build();
            }

            // Null-safe extraction: Azure Speech SDK có thể trả null cho một số field
            // (đặc biệt prosodyScore — chỉ hỗ trợ locale en-US, ja-JP luôn null).
            double accuracyScore     = safeScore(pronResult.getAccuracyScore());
            double fluencyScore      = safeScore(pronResult.getFluencyScore());
            double prosodyScore      = safeScore(pronResult.getProsodyScore());
            double completenessScore = safeScore(pronResult.getCompletenessScore());

            // Overall = weighted average for ja-JP (prosody is always 0 for non-en-US locales)
            // Accuracy 50%: correct mora/phoneme production is foundational for Japanese
            // Fluency 30%: natural pacing with minimal hesitation
            // Completeness 20%: all expected words/morae were spoken
            double overallScore = (accuracyScore * 0.5 + fluencyScore * 0.3
                    + completenessScore * 0.2);

            // Parse word-level and phoneme-level details from the JSON response
            String jsonResponse = result.getProperties().getProperty(
                    PropertyId.SpeechServiceResponse_JsonResult);
            List<AssessmentResult.WordAssessment> wordDetails = parseWordDetails(jsonResponse);

            return AssessmentResult.builder()
                    .success(true)
                    .overallScore(overallScore)
                    .accuracyScore(accuracyScore)
                    .fluencyScore(fluencyScore)
                    .prosodyScore(prosodyScore)
                    .completenessScore(completenessScore)
                    .transcribedText(result.getText())
                    .rawResponse(jsonResponse != null ? jsonResponse : "{}")
                    .providerName("AZURE")
                    .wordDetails(wordDetails)
                    .build();

        } catch (Exception e) {
            log.error("Failed to parse Azure pronunciation result: {}", e.getMessage());
            return AssessmentResult.builder()
                    .success(true)
                    .transcribedText(result.getText())
                    .rawResponse("{\"error\":\"parse_failed\"}")
                    .providerName("AZURE")
                    .build();
        }
    }

    /** Null-safe score extraction — Azure SDK có thể trả null (boxed Double) cho một số metric/locale. */
    private static double safeScore(Double score) {
        return score != null ? score : 0.0;
    }

    /**
     * Parses word-level and phoneme-level assessment details from the Azure JSON response.
     * Azure response structure:
     * NBest[0].Words[].PronunciationAssessment.{AccuracyScore, ErrorType}
     * NBest[0].Words[].Phonemes[].PronunciationAssessment.AccuracyScore
     */
    private List<AssessmentResult.WordAssessment> parseWordDetails(String jsonResponse) {
        List<AssessmentResult.WordAssessment> wordDetails = new ArrayList<>();
        if (jsonResponse == null || jsonResponse.isBlank()) {
            return wordDetails;
        }

        try {
            JsonNode root = objectMapper.readTree(jsonResponse);
            JsonNode nBest = root.path("NBest");
            if (!nBest.isArray() || nBest.isEmpty()) {
                return wordDetails;
            }

            JsonNode words = nBest.get(0).path("Words");
            if (!words.isArray()) {
                return wordDetails;
            }

            for (JsonNode wordNode : words) {
                JsonNode wordAssessment = wordNode.path("PronunciationAssessment");

                // Parse phonemes for this word
                List<AssessmentResult.PhonemeAssessment> phonemes = new ArrayList<>();
                JsonNode phonemeNodes = wordNode.path("Phonemes");
                if (phonemeNodes.isArray()) {
                    for (JsonNode phonemeNode : phonemeNodes) {
                        JsonNode phonemeAssessment = phonemeNode.path("PronunciationAssessment");
                        phonemes.add(AssessmentResult.PhonemeAssessment.builder()
                                .phoneme(phonemeNode.path("Phoneme").asText(""))
                                .accuracyScore(phonemeAssessment.path("AccuracyScore").asDouble(0))
                                .build());
                    }
                }

                wordDetails.add(AssessmentResult.WordAssessment.builder()
                        .word(wordNode.path("Word").asText(""))
                        .accuracyScore(wordAssessment.path("AccuracyScore").asDouble(0))
                        .errorType(wordAssessment.path("ErrorType").asText("None"))
                        .phonemes(phonemes)
                        .build());
            }
        } catch (Exception e) {
            log.warn("Failed to parse word-level details from Azure response: {}", e.getMessage());
        }

        return wordDetails;
    }

    /**
     * Ensures audio data is in WAV PCM format for Azure Speech SDK.
     * If the input is already WAV, returns as-is.
     * For other formats, wraps raw PCM data with a WAV header (assumes 16kHz 16bit mono).
     */
    private byte[] ensureWavFormat(byte[] audioData, String format) {
        if (audioData == null || audioData.length == 0) {
            return audioData;
        }

        // Check if already WAV (starts with RIFF header)
        if (audioData.length > 4
                && audioData[0] == 'R' && audioData[1] == 'I'
                && audioData[2] == 'F' && audioData[3] == 'F') {
            return audioData;
        }

        // For raw PCM data, add WAV header
        if ("pcm".equalsIgnoreCase(format) || "raw".equalsIgnoreCase(format)) {
            return addWavHeader(audioData, 16000, 16, 1);
        }

        // For other formats (mp3, ogg, webm, m4a), return as-is
        // Azure SDK PushAudioInputStream can handle some compressed formats
        return audioData;
    }

    private byte[] addWavHeader(byte[] pcmData, int sampleRate, int bitsPerSample, int channels) {
        int byteRate = sampleRate * channels * bitsPerSample / 8;
        int blockAlign = channels * bitsPerSample / 8;
        int dataSize = pcmData.length;
        int chunkSize = 36 + dataSize;

        ByteArrayOutputStream out = new ByteArrayOutputStream(44 + dataSize);
        try (DataOutputStream dos = new DataOutputStream(out)) {
            dos.writeBytes("RIFF");
            dos.writeInt(Integer.reverseBytes(chunkSize));
            dos.writeBytes("WAVE");
            dos.writeBytes("fmt ");
            dos.writeInt(Integer.reverseBytes(16)); // Subchunk1Size
            dos.writeShort(Short.reverseBytes((short) 1)); // AudioFormat (PCM)
            dos.writeShort(Short.reverseBytes((short) channels));
            dos.writeInt(Integer.reverseBytes(sampleRate));
            dos.writeInt(Integer.reverseBytes(byteRate));
            dos.writeShort(Short.reverseBytes((short) blockAlign));
            dos.writeShort(Short.reverseBytes((short) bitsPerSample));
            dos.writeBytes("data");
            dos.writeInt(Integer.reverseBytes(dataSize));
            dos.write(pcmData);
        } catch (IOException e) {
            log.error("Failed to create WAV header: {}", e.getMessage());
            return pcmData;
        }
        return out.toByteArray();
    }

    /**
     * Closes Azure Speech SDK resources in a daemon thread after the native async
     * recognition completes. Called only on timeout, where closing immediately
     * raises IllegalStateException.
     */
    private void scheduleDeferredCleanup(SpeechRecognizer recognizer,
                                          AudioConfig audioConfig,
                                          SpeechConfig speechConfig,
                                          Path tempWavFile) {
        Thread t = new Thread(() -> {
            try {
                // Give the SDK up to 10 s to finish before forcing close.
                Thread.sleep(10_000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } finally {
                try { if (recognizer != null) recognizer.close(); } catch (Exception ignored) {}
                try { if (audioConfig != null) audioConfig.close(); } catch (Exception ignored) {}
                try { if (speechConfig != null) speechConfig.close(); } catch (Exception ignored) {}
                if (tempWavFile != null) {
                    try { Files.deleteIfExists(tempWavFile); } catch (IOException ignored) {}
                }
            }
        }, "azure-speech-cleanup");
        t.setDaemon(true);
        t.start();
    }

    private PronunciationAssessmentGranularity mapGranularity(AzureSpeechProperties.GranularityLevel level) {
        return switch (level) {
            case FULL_TEXT -> PronunciationAssessmentGranularity.FullText;
            case WORD -> PronunciationAssessmentGranularity.Word;
            case PHONEME -> PronunciationAssessmentGranularity.Phoneme;
        };
    }
}
