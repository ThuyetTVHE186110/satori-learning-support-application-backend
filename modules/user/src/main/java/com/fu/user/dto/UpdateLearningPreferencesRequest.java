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

    @Pattern(regexp = "N[1-5]", message = "targetJlptLevel must be one of: N1, N2, N3, N4, N5")
    private String targetJlptLevel;

    @Min(value = 5, message = "dailyStudyGoalMinutes must be at least 5")
    @Max(value = 480, message = "dailyStudyGoalMinutes must be at most 480")
    private Integer dailyStudyGoalMinutes;

    @Pattern(regexp = "morning|afternoon|evening|night",
             message = "preferredStudyTime must be one of: morning, afternoon, evening, night")
    private String preferredStudyTime;

    private List<String> preferredTopics;

    @Pattern(regexp = "slow|normal|fast",
             message = "learningPace must be one of: slow, normal, fast")
    private String learningPace;

    @Pattern(regexp = "casual|polite|formal",
             message = "preferredFormality must be one of: casual, polite, formal")
    private String preferredFormality;

    @Pattern(regexp = "practical|creative|business|travel",
             message = "conversationStyle must be one of: practical, creative, business, travel")
    private String conversationStyle;

    private Boolean streakReminderEnabled;

    private LocalTime reminderTime;
}
