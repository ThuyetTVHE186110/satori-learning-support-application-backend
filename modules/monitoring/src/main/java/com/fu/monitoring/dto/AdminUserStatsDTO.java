package com.fu.monitoring.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Admin user statistics and metrics")
public class AdminUserStatsDTO {

    @Schema(description = "User count breakdown by role, status, auth provider, and gender")
    private UserCountBreakdown countBreakdown;

    @Schema(description = "New user registration metrics")
    private NewUserMetrics newUserMetrics;

    @Schema(description = "Active session statistics")
    private SessionMetrics sessionMetrics;

    @Schema(description = "Security and failed login metrics")
    private SecurityMetrics securityMetrics;

    @Schema(description = "User activity metrics")
    private ActivityMetrics activityMetrics;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "User count breakdown")
    public static class UserCountBreakdown {

        @Schema(description = "Total users by role (LEARNER, TEACHER, MANAGER, ADMIN)", example = "{\"LEARNER\": 1000, \"TEACHER\": 50}")
        private Map<String, Long> byRole;

        @Schema(description = "Total users by status (ACTIVE, PENDING_ACTIVATION, SUSPENDED, DELETED)")
        private Map<String, Long> byStatus;

        @Schema(description = "Total users by gender (MALE, FEMALE, OTHER, UNKNOWN)")
        private Map<String, Long> byGender;

        @Schema(description = "Grand total of all users")
        private Long totalUsers;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "New user registration metrics")
    public static class NewUserMetrics {

        @Schema(description = "New users registered today", example = "23")
        private Long today;

        @Schema(description = "New users registered this week", example = "145")
        private Long thisWeek;

        @Schema(description = "New users registered this month", example = "687")
        private Long thisMonth;

        @Schema(description = "Daily registration trend for last 30 days")
        private Map<LocalDate, Long> dailyTrend;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "Active session statistics")
    public static class SessionMetrics {

        @Schema(description = "Total active sessions", example = "234")
        private Long activeSessions;

        @Schema(description = "Active sessions by device type (mobile, desktop, tablet, unknown)")
        private Map<String, Long> byDeviceType;

        @Schema(description = "Active sessions by operating system (iOS, Android, Windows, Mac, Linux)")
        private Map<String, Long> byOs;

        @Schema(description = "Active sessions by browser (Chrome, Safari, Firefox, Edge, other)")
        private Map<String, Long> byBrowser;

        @Schema(description = "Average session duration in seconds", example = "1695")
        private Long avgSessionDurationSeconds;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "Security metrics")
    public static class SecurityMetrics {

        @Schema(description = "Failed login attempts in last 24 hours", example = "12")
        private Long failedLoginAttempts24h;

        @Schema(description = "Currently locked accounts", example = "3")
        private Long lockedAccounts;

        @Schema(description = "Suspended user accounts", example = "5")
        private Long suspendedAccounts;

        @Schema(description = "Daily failed login trend for last 7 days")
        private Map<LocalDate, Long> failedLoginTrend;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "User activity metrics")
    public static class ActivityMetrics {

        @Schema(description = "Users active today (with login/activity)", example = "456")
        private Long usersActiveToday;

        @Schema(description = "Users active this week", example = "1234")
        private Long usersActiveThisWeek;

        @Schema(description = "Users active this month", example = "5678")
        private Long usersActiveThisMonth;
    }
}
