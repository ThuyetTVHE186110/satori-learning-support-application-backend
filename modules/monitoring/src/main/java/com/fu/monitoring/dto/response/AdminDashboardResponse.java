package com.fu.monitoring.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Admin overview dashboard — all widgets in one response")
public class AdminDashboardResponse {

    // ── Top 4 KPI cards ──────────────────────────────────────────────────────

    @Schema(description = "Tổng tài khoản — total non-deleted users", example = "1250")
    private long totalAccounts;

    @Schema(description = "Hoạt động hôm nay — users whose lastLoginAt is today", example = "240")
    private long activeToday;

    @Schema(description = "Phiên AI hôm nay — PracticeSession + ChatSession + ConversationSession created today", example = "850")
    private long aiSessionsToday;

    @Schema(description = "Bài nộp hôm nay — AssignmentSubmissions with submittedAt today", example = "95")
    private long submissionsToday;

    // ── Tăng trưởng người dùng ───────────────────────────────────────────────

    @Schema(description = "Daily new-user registrations for the last 7 days, sorted ascending by date")
    private List<DailyCount> userGrowth7Days;

    @Schema(description = "Daily new-user registrations for the last 30 days, sorted ascending by date")
    private List<DailyCount> userGrowth30Days;

    // ── Phân bố vai trò ──────────────────────────────────────────────────────

    @Schema(description = "Role distribution as percentage of total users. Keys: LEARNER, TEACHER, CONTENT_MANAGER, OPERATION_MANAGER, ADMIN")
    private Map<String, Double> roleDistributionPercent;

    // ── Sức khỏe hệ thống ────────────────────────────────────────────────────

    @Schema(description = "System health metrics")
    private SystemHealth systemHealth;

    // ── Phiên AI theo ngày ───────────────────────────────────────────────────

    @Schema(description = "Daily AI session count for the last 7 days (all session types combined), sorted ascending by date")
    private List<DailyCount> aiSessionTrend7Days;

    // ── Nested types ─────────────────────────────────────────────────────────

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "A single day's count value for trend charts")
    public static class DailyCount {
        @Schema(description = "Date", example = "2026-05-01")
        private LocalDate date;

        @Schema(description = "Count for that day", example = "42")
        private long count;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "System health snapshot")
    public static class SystemHealth {

        @Schema(description = "Overall system status: UP or DOWN", example = "UP")
        private String status;

        @Schema(description = "CPU usage percentage (0–100)", example = "42.0")
        private double cpuUsagePercent;

        @Schema(description = "JVM heap usage percentage (0–100)", example = "65.0")
        private double ramUsagePercent;

        @Schema(description = "Database connectivity status: UP or DOWN", example = "UP")
        private String dbStatus;

        @Schema(description = "OpenAI API reachability: UP or DOWN", example = "UP")
        private String aiServiceStatus;

        @Schema(description = "p95 HTTP response time in milliseconds", example = "120.0")
        private double p95ResponseTimeMs;
    }
}
