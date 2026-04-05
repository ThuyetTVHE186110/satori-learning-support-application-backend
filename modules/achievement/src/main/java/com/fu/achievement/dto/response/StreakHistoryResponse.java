package com.fu.achievement.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Streak history for a period with daily breakdown")
public class StreakHistoryResponse {
    
    @JsonProperty("period")
    @Schema(description = "Period information")
    private StreakPeriodInfo period;
    
    @JsonProperty("summary")
    @Schema(description = "Summary statistics for period")
    private StreakSummary summary;
    
    @JsonProperty("daily_records")
    @Schema(description = "Daily streak records (newest first)")
    private List<StreakHistoryItem> dailyRecords;
}
