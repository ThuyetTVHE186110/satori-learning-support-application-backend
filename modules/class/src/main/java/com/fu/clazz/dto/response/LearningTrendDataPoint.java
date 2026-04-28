package com.fu.clazz.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Response DTO for a single data point in the learning trend chart.
 * Represents aggregated metrics for a time period (weekly/monthly).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LearningTrendDataPoint {

    /**
     * Period label (e.g., "Week 1", "2024-04-01 - 2024-04-07", or timestamp)
     */
    private String periodLabel;

    /**
     * Period start timestamp
     */
    private LocalDateTime periodStart;

    /**
     * Period end timestamp
     */
    private LocalDateTime periodEnd;

    /**
     * Class-level completion percentage for this period
     * (lessons with assignments submitted / total lessons) * 100
     * Range: 0-100
     */
    private Double completionPercentage;

    /**
     * Average score across all learners' submissions in this period
     * Scale 0-10
     */
    private BigDecimal averageScore;

    /**
     * Number of learners who had activity in this period
     */
    private Long activeLearnersCount;

    /**
     * Number of assignments submitted in this period
     */
    private Long submissionsCount;
}
