package com.fu.user.dto;

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

    // Personalization insights
    private List<Object> weakPoints;
    private List<Object> strongPoints;

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
                .build();
    }
}
