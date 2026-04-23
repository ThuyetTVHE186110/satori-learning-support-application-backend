package com.fu.monitoring.service;

import com.fu.monitoring.dto.response.AchievementMetricsResponse;
import com.fu.monitoring.dto.response.ClassManagementDashboardResponse;
import com.fu.monitoring.dto.response.ClassStatusChartResponse;
import com.fu.monitoring.dto.response.DashboardKpiResponse;
import com.fu.monitoring.dto.response.PopularBadgesResponse;
import com.fu.monitoring.dto.response.PriorityClassesResponse;
import com.fu.monitoring.dto.response.RareBadgesResponse;
import com.fu.monitoring.dto.response.StudentTrendResponse;

/**
 * Service for the Class Management Dashboard used by Operation Managers.
 */
public interface ClassManagementDashboardService {

    /**
     * Get the complete dashboard with all 6 sections aggregated.
     */
    ClassManagementDashboardResponse getDashboard();

    /**
     * Get KPI summary (4 metrics: total classes, active classes, total students, total teachers).
     */
    DashboardKpiResponse getKpiSummary();

    /**
     * Get class status distribution for the donut chart.
     */
    ClassStatusChartResponse getStatusChart();

    /**
     * Get student enrollment trend for the last 6 months.
     */
    StudentTrendResponse getStudentTrend();

    /**
     * Get top 10 priority classes requiring attention (OVERDUE, ENDING_SOON, STARTING_SOON, INCOMPLETE).
     */
    PriorityClassesResponse getPriorityClasses();

    /**
     * Get achievement metrics (total badges awarded, badge award rate, total EXP awarded).
     */
    AchievementMetricsResponse getAchievementMetrics();

    /**
     * Get top 5 most popular badges.
     */
    PopularBadgesResponse getPopularBadges();

    /**
     * Get top 5 rarest badges — "Thành tích ít người đạt" (fewest earners, awardCount > 0).
     */
    RareBadgesResponse getRareBadges();
}
