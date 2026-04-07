package com.fu.monitoring.service;

import com.fu.monitoring.dto.DashboardStatsResponse;

public interface SystemMonitoringService {
    /**
     * Get system dashboard statistics.
     * 
     * @return DashboardStatsResponse containing system health, usage statistics, and performance metrics.
     */
    DashboardStatsResponse getDashboardStats();
}
