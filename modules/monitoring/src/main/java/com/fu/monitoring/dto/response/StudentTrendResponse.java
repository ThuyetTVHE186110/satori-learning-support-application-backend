package com.fu.monitoring.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 6-month student enrollment trend for the line chart.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StudentTrendResponse {
    private List<MonthlyEnrollmentDto> data;
}
