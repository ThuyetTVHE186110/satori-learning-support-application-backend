package com.fu.pronunciation.provider;

import com.fu.pronunciation.config.AzureSpeechProperties;
import com.microsoft.cognitiveservices.speech.ResultReason;
import com.microsoft.cognitiveservices.speech.SpeechConfig;
import com.microsoft.cognitiveservices.speech.SpeechSynthesisOutputFormat;
import com.microsoft.cognitiveservices.speech.SpeechSynthesisResult;
import com.microsoft.cognitiveservices.speech.SpeechSynthesizer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Azure Neural TTS. Synthesizes roleplay AI messages to MP3 for Cloudinary storage.
 * SSML wraps an optional expressive style; unknown styles are silently ignored by Azure
 * (the plain voice output is returned instead of failing).
 */
@Service
@Slf4j
public class AzureTtsProvider {

    private final AzureSpeechProperties properties;

    private static final Set<String> POLITE_VOICES = Set.of(
            "ja-JP-NanamiNeural", "ja-JP-KeitaNeural", "ja-JP-MayuNeural");
    private static final Set<String> CASUAL_VOICES = Set.of(
            "ja-JP-AoiNeural", "ja-JP-ShioriNeural", "ja-JP-DaichiNeural");
    private static final Set<String> ALL_VOICES;
    static {
        ALL_VOICES = new java.util.HashSet<>();
        ALL_VOICES.addAll(POLITE_VOICES);
        ALL_VOICES.addAll(CASUAL_VOICES);
    }

    public AzureTtsProvider(AzureSpeechProperties properties) {
        this.properties = properties;
    }

    public boolean isAvailable() {
        return properties.isEnabled()
                && properties.getTts().isEnabled()
                && properties.getSubscriptionKey() != null
                && !properties.getSubscriptionKey().isBlank();
    }

    /**
     * @param text       plain Japanese text (no SSML)
     * @param voice      requested voice name (e.g., ja-JP-NanamiNeural); falls back to defaultVoice if unknown
     * @param style      optional Azure style (cheerful, sad, angry, customer-service, chat); may be null/blank
     * @param formality  "polite" | "casual" | null — if set, restricts voice to matching allowlist
     * @return MP3 bytes on success, null on failure
     */
    public byte[] synthesize(String text, String voice, String style, String formality) {
        if (!isAvailable()) {
            return null;
        }
        if (text == null || text.isBlank()) {
            return null;
        }

        String resolvedVoice = resolveVoice(voice, formality);
        String ssml = buildSsml(text, resolvedVoice, style);

        SpeechConfig speechConfig = null;
        SpeechSynthesizer synth = null;
        try {
            speechConfig = SpeechConfig.fromSubscription(
                    properties.getSubscriptionKey(), properties.getRegion());
            speechConfig.setSpeechSynthesisOutputFormat(mapOutputFormat(properties.getTts().getOutputFormat()));

            synth = new SpeechSynthesizer(speechConfig, null);
            Future<SpeechSynthesisResult> future = synth.SpeakSsmlAsync(ssml);
            SpeechSynthesisResult result = future.get(properties.getTimeout(), TimeUnit.MILLISECONDS);

            if (result.getReason() == ResultReason.SynthesizingAudioCompleted) {
                byte[] audio = result.getAudioData();
                result.close();
                return audio;
            }
            log.warn("Azure TTS synthesis failed, reason={}", result.getReason());
            result.close();
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            log.error("Azure TTS error: {}", e.getMessage(), e);
            return null;
        } finally {
            if (synth != null) synth.close();
            if (speechConfig != null) speechConfig.close();
        }
    }

    private String resolveVoice(String requested, String formality) {
        String defaultVoice = properties.getTts().getDefaultVoice();
        if (requested == null || requested.isBlank()) {
            return applyFormalityGuard(defaultVoice, formality, defaultVoice);
        }
        if (!ALL_VOICES.contains(requested)) {
            log.debug("Unknown TTS voice '{}', using default '{}'", requested, defaultVoice);
            return applyFormalityGuard(defaultVoice, formality, defaultVoice);
        }
        return applyFormalityGuard(requested, formality, defaultVoice);
    }

    private String applyFormalityGuard(String voice, String formality, String fallback) {
        if (formality == null || formality.isBlank()) return voice;
        String f = formality.toLowerCase();
        if ("polite".equals(f) || "formal".equals(f)) {
            return POLITE_VOICES.contains(voice) ? voice
                    : (POLITE_VOICES.contains(fallback) ? fallback : "ja-JP-NanamiNeural");
        }
        if ("casual".equals(f) || "plain".equals(f)) {
            return CASUAL_VOICES.contains(voice) ? voice
                    : (CASUAL_VOICES.contains(fallback) ? fallback : "ja-JP-AoiNeural");
        }
        return voice;
    }

    private String buildSsml(String text, String voice, String style) {
        String escaped = escapeXml(text);
        String inner;
        if (style != null && !style.isBlank()) {
            String safeStyle = escapeXml(style.trim());
            inner = "<mstts:express-as style=\"" + safeStyle + "\">" + escaped + "</mstts:express-as>";
        } else {
            inner = escaped;
        }
        return "<speak version=\"1.0\" xmlns=\"http://www.w3.org/2001/10/synthesis\" "
                + "xmlns:mstts=\"https://www.w3.org/2001/mstts\" xml:lang=\"ja-JP\">"
                + "<voice name=\"" + voice + "\">" + inner + "</voice></speak>";
    }

    private static String escapeXml(String s) {
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    private SpeechSynthesisOutputFormat mapOutputFormat(String name) {
        if (name == null) return SpeechSynthesisOutputFormat.Audio16Khz32KBitRateMonoMp3;
        try {
            return SpeechSynthesisOutputFormat.valueOf(name);
        } catch (IllegalArgumentException e) {
            log.warn("Unknown TTS output format '{}', falling back to Audio16Khz32KBitRateMonoMp3", name);
            return SpeechSynthesisOutputFormat.Audio16Khz32KBitRateMonoMp3;
        }
    }
}
