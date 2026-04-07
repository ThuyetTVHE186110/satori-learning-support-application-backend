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
@Schema(description = "Single day streak history record")
public class StreakHistoryItem {
    
    @JsonProperty("date")
    @Schema(description = "Date of streak record (YYYY-MM-DD)", example = "2026-04-05")
    private LocalDate date;
    
    @JsonProperty("streak_count")
    @Schema(description = "Running streak count on this date", example = "15")
    private Integer streakCount;
    
    @JsonProperty("had_activity")
    @Schema(description = "Whether user had activity this day", example = "true")
    private Boolean hadActivity;
    
    @JsonProperty("activity_type")
    @Schema(description = "Type of activity (ASSIGNMENT, PRACTICE, SPEAKING)", example = "ASSIGNMENT", nullable = true)
    private String activityType;
    
    @JsonProperty("exp_earned")
    @Schema(description = "EXP earned on this day", example = "15")
    private Integer expEarned;
    
    @JsonProperty("freeze_used")
    @Schema(description = "Whether freeze token was used", example = "false")
    private Boolean freezeUsed;
}
