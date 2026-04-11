package com.fu.user.dto;

import com.fu.infrastructure.learner.LearningInsightPoint;
import com.fu.infrastructure.learner.SkillScore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;

/**
 * Response DTO for user learning preferences.
 * Used in profile and preference update responses.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LearningPreferencesResponse {

    // Target JLPT level (N5 → N1)
    private String targetJlptLevel;

    // Daily study goal in minutes
    private Integer dailyStudyGoalMinutes;

    // Preferred time of day to study: morning | afternoon | evening | night
    private String preferredStudyTime;

    // AI conversation preferences
    private List<String> preferredTopics;
    private String learningPace;       // slow | normal | fast
    private String preferredFormality; // casual | polite | formal
    private String conversationStyle;  // practical | creative | business | travel

    // Reminder settings
    private Boolean streakReminderEnabled;
    private LocalTime reminderTime;

    // Personalization insights (typed)
    private List<LearningInsightPoint> weakPoints;
    private List<LearningInsightPoint> strongPoints;

    // Satori Pentagon skill radar scores
    private List<SkillScore> skillScores;

    /**
     * Build from entity, converting String[] to List<String>.
     */
    public static LearningPreferencesResponse from(com.fu.user.entity.UserLearningPreferences prefs) {
        if (prefs == null) return defaultPreferences();
        return LearningPreferencesResponse.builder()
                .targetJlptLevel(prefs.getTargetJlptLevel())
                .dailyStudyGoalMinutes(prefs.getDailyStudyGoalMinutes())
                .preferredStudyTime(prefs.getPreferredStudyTime())
                .preferredTopics(prefs.getPreferredTopics() != null
                        ? Arrays.asList(prefs.getPreferredTopics()) : List.of())
                .learningPace(prefs.getLearningPace())
                .preferredFormality(prefs.getPreferredFormality())
                .conversationStyle(prefs.getConversationStyle())
                .streakReminderEnabled(prefs.getStreakReminderEnabled())
                .reminderTime(prefs.getReminderTime())
                .weakPoints(prefs.getWeakPoints() != null ? prefs.getWeakPoints() : List.of())
                .strongPoints(prefs.getStrongPoints() != null ? prefs.getStrongPoints() : List.of())
                .skillScores(prefs.getSkillScores() != null ? prefs.getSkillScores() : defaultSkillScores())
                .build();
    }

    /**
     * Return sensible defaults when no preferences row exists yet.
     */
    public static LearningPreferencesResponse defaultPreferences() {
        return LearningPreferencesResponse.builder()
                .targetJlptLevel("N5")
                .dailyStudyGoalMinutes(30)
                .preferredStudyTime(null)
                .preferredTopics(List.of())
                .learningPace("normal")
                .preferredFormality("polite")
                .conversationStyle(null)
                .streakReminderEnabled(true)
                .reminderTime(LocalTime.of(20, 0))
                .weakPoints(List.of())
                .strongPoints(List.of())
                .skillScores(defaultSkillScores())
                .build();
    }

    /**
     * Default Satori Pentagon scores — all zeros (no data yet).
     */
    private static List<SkillScore> defaultSkillScores() {
        return List.of(
                SkillScore.defaultScore("VOCABULARY"),
                SkillScore.defaultScore("GRAMMAR"),
                SkillScore.defaultScore("PRONUNCIATION"),
                SkillScore.defaultScore("FLUENCY"),
                SkillScore.defaultScore("SPEAKING")
        );
    }
}
