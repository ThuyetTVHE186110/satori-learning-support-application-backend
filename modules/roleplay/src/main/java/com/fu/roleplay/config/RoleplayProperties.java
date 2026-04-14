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

    /** OpenAI model for roleplay conversation AI. */
    private String modelName = "gpt-4.1-mini";

    /** OpenAI model for mission evaluation and session evaluation. */
    private String evaluatorModel = "gpt-4.1-mini";

    /** Redis cache TTL for system prompts, in hours. */
    private int redisSessionTtlHours = 2;

    /** Max conversation history messages sent to the AI for context. */
    private int maxContextMessages = 20;

    /** Max messages sent to the mission evaluator. */
    private int maxEvalMessages = 20;

    /** Max audio file size in MB. */
    private int maxAudioSizeMb = 10;

    /** 4-dimension evaluation weights for computeInternalOverall(). */
    private EvaluationWeights evaluationWeights = new EvaluationWeights();

    /** Legacy 3-dimension scoring weights (session-level, used by external callers). */
    private Scoring scoring = new Scoring();

    @PostConstruct
    public void validate() {
        double total = scoring.getMissionWeight() + scoring.getPronunciationWeight() + scoring.getLanguageWeight();
        if (Math.abs(total - 1.0) > 0.001) {
            log.warn("Roleplay scoring weights sum to {} (expected 1.0). Resetting to defaults.", total);
            scoring.setMissionWeight(0.35);
            scoring.setPronunciationWeight(0.30);
            scoring.setLanguageWeight(0.35);
        }
        double evalTotal = evaluationWeights.getAccuracy() + evaluationWeights.getPronunciation()
                + evaluationWeights.getTaskCompletion() + evaluationWeights.getComplexity();
        if (Math.abs(evalTotal - 1.0) > 0.001) {
            log.warn("Roleplay evaluation weights sum to {} (expected 1.0). Resetting to defaults.", evalTotal);
            evaluationWeights = new EvaluationWeights();
        }
    }

    @Getter
    @Setter
    public static class EvaluationWeights {
        private double accuracy = 0.30;
        private double pronunciation = 0.25;
        private double taskCompletion = 0.30;
        private double complexity = 0.15;
    }

    @Getter
    @Setter
    public static class Scoring {
        private double missionWeight = 0.35;
        private double pronunciationWeight = 0.30;
        private double languageWeight = 0.35;
    }
}
