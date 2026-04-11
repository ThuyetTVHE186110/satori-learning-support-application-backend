package com.fu.infrastructure.learner;

import java.time.LocalDateTime;

/**
 * Score for a single skill dimension in the "Satori Pentagon" radar chart.
 * Stored as JSONB list in UserLearningPreferences.skillScores.
 *
 * <p>Five dimensions: VOCABULARY, GRAMMAR, PRONUNCIATION, FLUENCY, SPEAKING</p>
 */
public record SkillScore(
        String skill,           // VOCABULARY | GRAMMAR | PRONUNCIATION | FLUENCY | SPEAKING
        double score,           // 0–100
        double previousScore,   // for delta/trend calculation
        String trend,           // UP | DOWN | STABLE
        int dataPoints,         // how many sessions contributed to this score
        LocalDateTime lastUpdated
) {

    public static String computeTrend(double oldScore, double newScore) {
        if (newScore > oldScore + 2) return "UP";
        if (newScore < oldScore - 2) return "DOWN";
        return "STABLE";
    }

    public static SkillScore defaultScore(String skill) {
        return new SkillScore(skill, 0.0, 0.0, "STABLE", 0, null);
    }

    public SkillScore withNewScore(double eventScore) {
        double updated = this.dataPoints == 0
                ? eventScore
                : this.score * 0.7 + eventScore * 0.3;
        return new SkillScore(
                this.skill,
                Math.round(updated * 100.0) / 100.0,
                this.score,
                computeTrend(this.score, updated),
                this.dataPoints + 1,
                LocalDateTime.now()
        );
    }
}
