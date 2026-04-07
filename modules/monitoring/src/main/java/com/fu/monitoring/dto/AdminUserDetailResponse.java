package com.fu.monitoring.dto;

import com.fu.user.entity.UserRole;
import com.fu.user.entity.UserStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Detailed admin view of a user")
public class AdminUserDetailResponse {

    @Schema(description = "User ID", example = "550e8400-e29b-41d4-a716-446655440000")
    private UUID id;

    @Schema(description = "User's full name", example = "John Doe")
    private String fullName;

    @Schema(description = "User's email address", example = "john@example.com")
    private String email;

    @Schema(description = "User's phone number", example = "+84912345678")
    private String phoneNumber;

    @Schema(description = "User's date of birth", example = "1990-01-15")
    private LocalDate dateOfBirth;

    @Schema(description = "User's role", example = "LEARNER")
    private UserRole role;

    @Schema(description = "Account status", example = "ACTIVE")
    private UserStatus status;

    @Schema(description = "Authentication provider (LOCAL or GOOGLE)", example = "LOCAL")
    private String authProvider;

    @Schema(description = "Email verification status")
    private Boolean emailVerified;

    @Schema(description = "Account creation timestamp")
    private LocalDateTime createdAt;

    @Schema(description = "Last login timestamp")
    private LocalDateTime lastLoginAt;

    @Schema(description = "Last account update timestamp")
    private LocalDateTime updatedAt;

    @Schema(description = "User's active sessions", example = "1")
    private SessionSummary activeSessions;

    @Schema(description = "User's statistics (total activities)")
    private UserStatistics statistics;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "Summary of user's active sessions")
    public static class SessionSummary {

        @Schema(description = "Total active sessions", example = "1")
        private Long count;

        @Schema(description = "List of active sessions")
        private List<SessionDetail> sessions;

        @Data
        @Builder
        @NoArgsConstructor
        @AllArgsConstructor
        public static class SessionDetail {

            @Schema(description = "Device information (OS, type, browser)")
            private String device;

            @Schema(description = "IP address")
            private String ipAddress;

            @Schema(description = "Last activity time")
            private LocalDateTime lastActiveAt;

            @Schema(description = "Session creation time")
            private LocalDateTime createdAt;
        }
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "User activity statistics")
    public static class UserStatistics {

        @Schema(description = "Total assignments submitted", example = "45")
        private Long totalAssignments;

        @Schema(description = "Total practice sessions completed", example = "120")
        private Long totalPracticeSessions;

        @Schema(description = "Total badges earned", example = "8")
        private Long totalBadges;

        @Schema(description = "Current streak (days)", example = "15")
        private Integer currentStreak;

        @Schema(description = "Average grade on assignments", example = "85.5")
        private Double averageGrade;
    }
}
