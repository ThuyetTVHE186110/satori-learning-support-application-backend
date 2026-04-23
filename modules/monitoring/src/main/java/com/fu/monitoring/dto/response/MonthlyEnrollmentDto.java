package com.fu.monitoring.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Single data point for the student enrollment trend line chart.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MonthlyEnrollmentDto {
    /** Vietnamese month label, e.g. "THÁNG 1", "THÁNG 2" */
    private String month;
    private Long enrollmentCount;
}
