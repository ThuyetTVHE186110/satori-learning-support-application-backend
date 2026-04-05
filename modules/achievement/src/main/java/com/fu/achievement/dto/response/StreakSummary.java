package com.fu.achievement.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Summary statistics for streak period")
public class StreakSummary {
    
    @JsonProperty("active_count")
    @Schema(description = "Number of days with activity", example = "5")
    private Integer activeCount;
    
    @JsonProperty("total_days")
    @Schema(description = "Total days in period", example = "7")
    private Integer totalDays;
    
    @JsonProperty("activity_percentage")
    @Schema(description = "Percentage of active days (0-100)", example = "71")
    private Integer activityPercentage;
    
    @JsonProperty("current_streak")
    @Schema(description = "Active streak within this period", example = "3")
    private Integer currentStreak;
}
