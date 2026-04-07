package com.fu.achievement.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * Response indicating whether an activity triggered a first-activity-of-day streak event.
 * Returned when activity is recorded (submit, complete session) to signal FE to show celebration UI.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StreakNotificationResponse {

    /**
     * Whether this activity was the FIRST activity of today.
     * FE uses this to decide whether to show celebration animation/notification.
     */
    @JsonProperty("is_first_activity_today")
    private boolean isFirstActivityToday;

    /**
     * Current streak count after this activity.
     */
    @JsonProperty("current_streak")
    private Integer currentStreak;

    /**
     * Longest streak ever achieved by the user.
     */
    @JsonProperty("longest_streak")
    private Integer longestStreak;

    /**
     * Date of last activity (today if first activity triggered, or previous date).
     */
    @JsonProperty("streak_last_date")
    private LocalDate streakLastDate;
}
