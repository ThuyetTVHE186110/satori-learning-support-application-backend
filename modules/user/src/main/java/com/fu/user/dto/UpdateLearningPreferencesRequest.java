package com.fu.user.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalTime;
import java.util.List;

/**
 * Request DTO for updating learning preferences.
 * All fields are optional — only provided fields are updated.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateLearningPreferencesRequest {

    @Min(value = 5, message = "dailyStudyGoalMinutes must be at least 5")
    @Max(value = 480, message = "dailyStudyGoalMinutes must be at most 480")
    private Integer dailyStudyGoalMinutes;

    private List<String> preferredTopics;

    @Pattern(regexp = "casual|polite|formal",
             message = "preferredFormality must be one of: casual, polite, formal")
    private String preferredFormality;

    /**
     * Forwarded to notification_prefs.daily_reminder_enabled — not stored in learning_prefs.
     * Kept here so the settings screen can send a single PUT for all preferences.
     */
    private Boolean streakReminderEnabled;

    /**
     * Forwarded to notification_prefs.daily_reminder_time — not stored in learning_prefs.
     * Kept here so the settings screen can send a single PUT for all preferences.
     */
    private LocalTime reminderTime;
}
