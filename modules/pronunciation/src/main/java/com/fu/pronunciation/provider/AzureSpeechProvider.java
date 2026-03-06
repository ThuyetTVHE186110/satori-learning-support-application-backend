package com.fu.pronunciation.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fu.pronunciation.config.AzureSpeechProperties;
import com.fu.pronunciation.dto.AssessmentRequest;
import com.fu.pronunciation.dto.AssessmentResult;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import com.microsoft.cognitiveservices.speech.*;
import com.microsoft.cognitiveservices.speech.audio.*;
import com.microsoft.cognitiveservices.speech.PronunciationAssessmentConfig;
import com.microsoft.cognitiveservices.speech.PronunciationAssessmentGradingSystem;
import com.microsoft.cognitiveservices.speech.PronunciationAssessmentGranularity;
import com.microsoft.cognitiveservices.speech.PronunciationAssessmentResult;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
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

        try {
            // 1. Create SpeechConfig
            speechConfig = SpeechConfig.fromSubscription(
                    properties.getSubscriptionKey(), properties.getRegion());
            speechConfig.setSpeechRecognitionLanguage(properties.getLanguage());

            // 2. Create PronunciationAssessmentConfig
            PronunciationAssessmentGranularity granularity = mapGranularity(properties.getGranularity());
            PronunciationAssessmentConfig pronConfig = new PronunciationAssessmentConfig(
                    request.getReferenceText(),
                    PronunciationAssessmentGradingSystem.HundredMark,
                    granularity,
                    true // enableMiscue
            );
            pronConfig.enableProsodyAssessment();

            // 3. Convert audio bytes to PushAudioInputStream
            byte[] wavBytes = ensureWavFormat(request.getAudioData(), request.getAudioFormat());
            PushAudioInputStream pushStream = AudioInputStream.createPushStream(
                    AudioStreamFormat.getWaveFormatPCM(16000, (short) 16, (short) 1));
            pushStream.write(wavBytes);
            pushStream.close();

            audioConfig = AudioConfig.fromStreamInput(pushStream);

            // 4. Create recognizer and apply pronunciation assessment
            recognizer = new SpeechRecognizer(speechConfig, audioConfig);
            pronConfig.applyTo(recognizer);

            // 5. Recognize
            long timeoutMs = properties.getTimeout();
            SpeechRecognitionResult result = recognizer.recognizeOnceAsync()
                    .get(timeoutMs, TimeUnit.MILLISECONDS);

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
            return AssessmentResult.failure("Recognition timed out");
        } catch (Exception e) {
            log.error("Azure Speech assessment error: {}", e.getMessage(), e);
            return AssessmentResult.failure("Azure assessment failed: " + e.getMessage());
        } finally {
            if (recognizer != null) recognizer.close();
            if (audioConfig != null) audioConfig.close();
            if (speechConfig != null) speechConfig.close();
        }
    }

    @Override
    public boolean isAvailable() {
        return properties.isEnabled()
                && properties.getSubscriptionKey() != null
                && !properties.getSubscriptionKey().isBlank();
    }

    private AssessmentResult parseRecognitionResult(SpeechRecognitionResult result) {
        try {
            PronunciationAssessmentResult pronResult =
                    PronunciationAssessmentResult.fromResult(result);

            double accuracyScore = pronResult.getAccuracyScore();
            double fluencyScore = pronResult.getFluencyScore();
            double prosodyScore = pronResult.getProsodyScore();
            double completenessScore = pronResult.getCompletenessScore();

            // Overall = weighted average (Azure's default weighting)
            double overallScore = (accuracyScore * 0.4 + fluencyScore * 0.2
                    + completenessScore * 0.2 + prosodyScore * 0.2);

            // Parse word-level details
            List<AssessmentResult.WordAssessment> wordDetails = new ArrayList<>();
            var words = pronResult.getWords();
            if (words != null) {
                for (var word : words) {
                    List<AssessmentResult.PhonemeAssessment> phonemes = new ArrayList<>();
                    var wordPhonemes = word.getPhonemes();
                    if (wordPhonemes != null) {
                        for (var phoneme : wordPhonemes) {
                            phonemes.add(AssessmentResult.PhonemeAssessment.builder()
                                    .phoneme(phoneme.getPhoneme())
                                    .accuracyScore(phoneme.getAccuracyScore())
                                    .build());
                        }
                    }
                    wordDetails.add(AssessmentResult.WordAssessment.builder()
                            .word(word.getWord())
                            .accuracyScore(word.getAccuracyScore())
                            .errorType(word.getErrorType())
                            .phonemes(phonemes)
                            .build());
                }
            }

            // Raw response as JSON for storage
            String rawJson = serializeRawResult(pronResult, result.getText());

            return AssessmentResult.builder()
                    .success(true)
                    .overallScore(overallScore)
                    .accuracyScore(accuracyScore)
                    .fluencyScore(fluencyScore)
                    .prosodyScore(prosodyScore)
                    .completenessScore(completenessScore)
                    .transcribedText(result.getText())
                    .rawResponse(rawJson)
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

    private String serializeRawResult(PronunciationAssessmentResult pronResult, String text) {
        try {
            var raw = new java.util.LinkedHashMap<String, Object>();
            raw.put("provider", "AZURE");
            raw.put("text", text);
            raw.put("accuracyScore", pronResult.getAccuracyScore());
            raw.put("fluencyScore", pronResult.getFluencyScore());
            raw.put("prosodyScore", pronResult.getProsodyScore());
            raw.put("completenessScore", pronResult.getCompletenessScore());
            return objectMapper.writeValueAsString(raw);
        } catch (Exception e) {
            return "{}";
        }
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

    private PronunciationAssessmentGranularity mapGranularity(AzureSpeechProperties.GranularityLevel level) {
        return switch (level) {
            case FULL_TEXT -> PronunciationAssessmentGranularity.FullText;
            case WORD -> PronunciationAssessmentGranularity.Word;
            case PHONEME -> PronunciationAssessmentGranularity.Phoneme;
        };
    }
}
