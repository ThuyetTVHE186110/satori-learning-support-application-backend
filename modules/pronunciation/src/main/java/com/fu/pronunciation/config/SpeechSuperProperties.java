package com.fu.pronunciation.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "pronunciation.speechsuper")
public class SpeechSuperProperties {

    private String appKey = "";
    private String secretKey = "";
    private String baseUrl = "https://api.speechsuper.com";
    private int timeout = 30000;
}
