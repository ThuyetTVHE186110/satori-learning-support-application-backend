package com.fu.pronunciation.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "pronunciation.azure")
public class AzureSpeechProperties {

    private boolean enabled = false;
    private String subscriptionKey = "";
    private String region = "japaneast";
    private String language = "ja-JP";
    private int timeout = 30000;
    private GranularityLevel granularity = GranularityLevel.PHONEME;

    private Tts tts = new Tts();

    public enum GranularityLevel {
        FULL_TEXT, WORD, PHONEME
    }

    @Getter
    @Setter
    public static class Tts {
        private boolean enabled = true;
        /** Default voice when LLM doesn't specify or picks an unknown one. */
        private String defaultVoice = "ja-JP-NanamiNeural";
        /** Audio format: mp3 to minimize Cloudinary storage; Azure supports Audio16Khz32KBitRateMonoMp3. */
        private String outputFormat = "Audio16Khz32KBitRateMonoMp3";
        /** Cloudinary folder where synthesized audio lives. */
        private String cloudinaryFolder = "roleplay/tts";
    }
}
