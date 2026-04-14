package com.fu.roleplay.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Slf4j
@Component
@ConfigurationProperties(prefix = "roleplay")
public class RoleplayProperties {

    private int maxMessages = 50;
    private int maxActiveSessions = 3;

    /** Hours of inactivity after which an ACTIVE session is auto-abandoned. */
    private int sessionInactivityHours = 24;

    private Scoring scoring = new Scoring();

    @PostConstruct
    public void validateScoring() {
        double total = scoring.getMissionWeight() + scoring.getPronunciationWeight() + scoring.getLanguageWeight();
        if (Math.abs(total - 1.0) > 0.001) {
            log.warn("Roleplay scoring weights sum to {} (expected 1.0). Resetting to defaults.", total);
            scoring.setMissionWeight(0.35);
            scoring.setPronunciationWeight(0.30);
            scoring.setLanguageWeight(0.35);
        }
    }

    @Getter
    @Setter
    public static class Scoring {
        private double missionWeight = 0.35;
        private double pronunciationWeight = 0.30;
        private double languageWeight = 0.35;
    }
}
