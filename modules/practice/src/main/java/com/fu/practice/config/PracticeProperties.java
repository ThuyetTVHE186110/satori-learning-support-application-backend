package com.fu.practice.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Configuration for the practice module.
 * Properties under {@code practice.*} in application.yml.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "practice")
public class PracticeProperties {

    /**
     * OpenAI model for exercise generation. Isolated from global default so
     * practice gen can be upgraded independently of other modules.
     * Override via PRACTICE_EXERCISE_MODEL env var.
     */
    private String exerciseModel = "gpt-4.1-mini-2025-04-14";

    /** Timeout in seconds for parallel LLM exercise generation. */
    private int llmTimeoutSeconds = 120;

    /** Timeout in seconds for RAG distractor retrieval. */
    private int ragTimeoutSeconds = 3;

    /** Hours before a recently-used item can be reused in a new session. */
    private int antiRepeatWindowHours = 24;

    /** EMA smoothing factor for mastery calculation (0.0–1.0). */
    private double emaAlpha = 0.4;

    /** Initial EMA value for new items. */
    private double initialEma = 0.5;

    /** Mastery level thresholds — raw score boundaries for levels 0→1, 1→2, 2→3. */
    private List<Double> masteryThresholds = List.of(0.40, 0.70, 0.90);

    /** Component weights for mastery raw score calculation. */
    private MasteryWeights masteryWeights = new MasteryWeights();

    /** Score tier thresholds for session feedback messages. */
    private ScoreTiers scoreTiers = new ScoreTiers();

    @Getter
    @Setter
    public static class MasteryWeights {
        private double accuracy = 0.30;
        private double ema = 0.50;
        private double streak = 0.20;
    }

    @Getter
    @Setter
    public static class ScoreTiers {
        private int excellent = 80;
        private int good = 60;
    }
}
