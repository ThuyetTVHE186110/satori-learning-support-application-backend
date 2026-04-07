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
    
    @JsonProperty("current_streak")
    @Schema(description = "Current active streak count (days)", example = "10")
    private Integer currentStreak;
}
