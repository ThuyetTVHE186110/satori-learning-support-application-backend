package com.fu.monitoring.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Aggregate response for the complete Class Management Dashboard.
 * Combines all 6 sections into a single payload.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClassManagementDashboardResponse {
    private DashboardKpiResponse kpis;
    private ClassStatusChartResponse statusChart;
    private StudentTrendResponse studentTrend;
    private PriorityClassesResponse priorityClasses;
    private AchievementMetricsResponse achievements;
    private PopularBadgesResponse popularBadges;
    private RareBadgesResponse rareBadges;
    private LocalDateTime generatedAt;
}
