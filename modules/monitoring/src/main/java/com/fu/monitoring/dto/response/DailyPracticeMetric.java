package com.fu.monitoring.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DailyPracticeMetric {
    @JsonProperty("date")
    private LocalDate practiceDate;
    
    @JsonProperty("count")
    private Long practiceCount;
}
