package com.fu.monitoring.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DashboardStatsResponse {
    private SystemInfo system;
    private UsageStatistics statistics;
    private PerformanceMetrics performance;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SystemInfo {
        private String status;
        private String uptime;
        private double cpuUsage;
        private MemoryInfo memory;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MemoryInfo {
        private long total;
        private long used;
        private long free;
        private double usagePercentage;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UsageStatistics {
        private long totalUsers;
        private long totalCourses;
        private long totalClasses;
        private long totalAssignments;
        private long activeSessions;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PerformanceMetrics {
        private double p95ResponseTimeMs;
        private long errorCount24h;
        private Map<String, Long> requestCountPerStatus;
    }
}
