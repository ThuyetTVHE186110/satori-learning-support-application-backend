package com.fu.monitoring.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * KPI summary for the Class Management Dashboard.
 * Covers 4 top-level metrics shown as summary cards.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DashboardKpiResponse {
    private Long totalClasses;
    private Long activeClasses;
    private Long totalStudents;
    private Long totalTeachers;
}
