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

    public enum GranularityLevel {
        FULL_TEXT, WORD, PHONEME
    }
}
