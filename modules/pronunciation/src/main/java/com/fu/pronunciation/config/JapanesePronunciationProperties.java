package com.fu.pronunciation.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "pronunciation.japanese")
public class JapanesePronunciationProperties {

    private boolean enabled = true;
    private String serviceUrl = "http://localhost:8091";
    private int timeout = 30000;
    private double speechSuperWeight = 0.6;
    private double japaneseAssessmentWeight = 0.4;
}
