package com.fu.achievement.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Current streak status for learner")
public class CurrentStreakResponse {
    
    @JsonProperty("current_streak")
    @Schema(description = "Current consecutive days streak", example = "15")
    private Integer currentStreak;
    
    @JsonProperty("longest_streak")
    @Schema(description = "All-time longest streak", example = "42")
    private Integer longestStreak;
    
    @JsonProperty("streak_last_date")
    @Schema(description = "Last day with activity (YYYY-MM-DD)", example = "2026-04-05")
    private LocalDate streakLastDate;
    
    @JsonProperty("freeze_tokens_available")
    @Schema(description = "Remaining freeze tokens to maintain streak", example = "2")
    private Integer freezeTokensAvailable;
    
    @JsonProperty("days_until_break")
    @Schema(description = "Days user can miss before streak breaks (0=today, 1=tomorrow)", example = "1")
    @Builder.Default
    private Integer daysUntilBreak = 0;

    @JsonProperty("studied_minutes_today")
    @Schema(description = "Total minutes studied today across all practice sessions", example = "25")
    @Builder.Default
    private Integer studiedMinutesToday = 0;

    @JsonProperty("study_date")
    @Schema(description = "The date studied_minutes_today was last updated (YYYY-MM-DD)", example = "2026-04-12")
    private LocalDate studyDate;

    @JsonProperty("daily_goal_minutes")
    @Schema(description = "User's daily study goal in minutes (from learning preferences)", example = "30")
    @Builder.Default
    private Integer dailyGoalMinutes = 30;

    @JsonProperty("goal_progress_percent")
    @Schema(description = "Percentage of daily goal completed today (0–100)", example = "50")
    @Builder.Default
    private Integer goalProgressPercent = 0;
}
