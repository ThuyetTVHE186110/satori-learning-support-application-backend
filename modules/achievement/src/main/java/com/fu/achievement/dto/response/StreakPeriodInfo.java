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
@Schema(description = "Period information for streak history")
public class StreakPeriodInfo {
    
    @JsonProperty("start_date")
    @Schema(description = "Start date of period (YYYY-MM-DD)", example = "2026-03-29")
    private LocalDate startDate;
    
    @JsonProperty("end_date")
    @Schema(description = "End date of period (YYYY-MM-DD)", example = "2026-04-05")
    private LocalDate endDate;
    
    @JsonProperty("days_requested")
    @Schema(description = "Number of days requested", example = "7")
    private Integer daysRequested;
    
    @JsonProperty("streak_start_date")
    @Schema(description = "Date when current streak started (YYYY-MM-DD)", example = "2026-03-30", nullable = true)
    private LocalDate streakStartDate;
    
    @JsonProperty("cycle_number")
    @Schema(description = "Current streak cycle number (1-based, each cycle = 7 days)", example = "2")
    private Long cycleNumber;
}
