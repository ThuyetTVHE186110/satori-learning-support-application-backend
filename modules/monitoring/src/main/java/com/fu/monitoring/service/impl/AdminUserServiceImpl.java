package com.fu.monitoring.service.impl;

import com.fu.assignment.repository.AssignmentRepository;
import com.fu.user.entity.UserRole;
import com.fu.user.entity.UserStatus;
import com.fu.user.entity.AuthProvider;
import com.fu.common.exception.BusinessException;
import com.fu.monitoring.dto.AdminUserDetailResponse;
import com.fu.monitoring.dto.AdminUserStatsDTO;
import com.fu.monitoring.dto.UserSearchRequest;
import com.fu.monitoring.service.AdminUserService;
import com.fu.user.entity.User;
import com.fu.user.repository.UserRepository;
import com.fu.user.repository.UserSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class AdminUserServiceImpl implements AdminUserService {

    private final UserRepository userRepository;
    private final UserSessionRepository userSessionRepository;
    private final AssignmentRepository assignmentRepository;

    @Override
    public AdminUserStatsDTO getUserStatistics() {
        log.debug("Fetching comprehensive user statistics for admin dashboard");

        return AdminUserStatsDTO.builder()
                .countBreakdown(getCountBreakdown())
                .newUserMetrics(getNewUserMetrics())
                .sessionMetrics(getSessionMetrics())
                .securityMetrics(getSecurityMetrics())
                .activityMetrics(getActivityMetrics())
                .build();
    }

    private AdminUserStatsDTO.UserCountBreakdown getCountBreakdown() {
        Map<String, Long> byRole = new HashMap<>();
        byRole.put("LEARNER", userRepository.countByRole(UserRole.LEARNER));
        byRole.put("TEACHER", userRepository.countByRole(UserRole.TEACHER));
        byRole.put("CONTENT_MANAGER", userRepository.countByRole(UserRole.CONTENT_MANAGER));
        byRole.put("OPERATION_MANAGER", userRepository.countByRole(UserRole.OPERATION_MANAGER));
        byRole.put("ADMIN", userRepository.countByRole(UserRole.ADMIN));

        Map<String, Long> byStatus = new HashMap<>();
        byStatus.put("ACTIVE", userRepository.countByStatus(UserStatus.ACTIVE));
        byStatus.put("PENDING_ACTIVATION", userRepository.countByStatus(UserStatus.PENDING_ACTIVATION));
        byStatus.put("SUSPENDED", userRepository.countByStatus(UserStatus.SUSPENDED));
        byStatus.put("INACTIVE", userRepository.countByStatus(UserStatus.INACTIVE));

        Map<String, Long> byAuthProvider = new HashMap<>();
        byAuthProvider.put("LOCAL", userRepository.countByAuthProvider(AuthProvider.LOCAL));
        byAuthProvider.put("GOOGLE", userRepository.countByAuthProvider(AuthProvider.GOOGLE));

        Map<String, Long> byGender = convertGenderDistribution(userRepository.findGenderDistribution());

        long totalUsers = byRole.values().stream().mapToLong(Long::longValue).sum();

        return AdminUserStatsDTO.UserCountBreakdown.builder()
                .byRole(byRole)
                .byStatus(byStatus)
                .byAuthProvider(byAuthProvider)
                .byGender(byGender)
                .totalUsers(totalUsers)
                .build();
    }

    private Map<String, Long> convertGenderDistribution(List<Object[]> genderData) {
        return genderData.stream()
                .collect(Collectors.toMap(
                        row -> row[0] != null ? row[0].toString() : "UNKNOWN",
                        row -> row[1] != null ? ((Number) row[1]).longValue() : 0L
                ));
    }

    private AdminUserStatsDTO.NewUserMetrics getNewUserMetrics() {
        LocalDate today = LocalDate.now();
        LocalDate startOfWeek = today.minusDays(today.getDayOfWeek().getValue() - 1);
        LocalDate startOfMonth = today.withDayOfMonth(1);
        LocalDate thirtyDaysAgo = today.minusDays(30);

        long newToday = userRepository.countNewUsersByDateRange(
                LocalDateTime.of(today, LocalTime.MIDNIGHT),
                LocalDateTime.of(today.plusDays(1), LocalTime.MIDNIGHT)
        );

        long newThisWeek = userRepository.countNewUsersByDateRange(
                LocalDateTime.of(startOfWeek, LocalTime.MIDNIGHT),
                LocalDateTime.of(today.plusDays(1), LocalTime.MIDNIGHT)
        );

        long newThisMonth = userRepository.countNewUsersByDateRange(
                LocalDateTime.of(startOfMonth, LocalTime.MIDNIGHT),
                LocalDateTime.of(today.plusDays(1), LocalTime.MIDNIGHT)
        );

        Map<LocalDate, Long> dailyTrend = convertTrendData(
                userRepository.findNewUserTrendByDateRange(thirtyDaysAgo, today)
        );

        return AdminUserStatsDTO.NewUserMetrics.builder()
                .today(newToday)
                .thisWeek(newThisWeek)
                .thisMonth(newThisMonth)
                .dailyTrend(dailyTrend)
                .build();
    }

    private Map<LocalDate, Long> convertTrendData(List<Object[]> trendData) {
        return trendData.stream()
                .collect(Collectors.toMap(
                        row -> (LocalDate) row[0],
                        row -> row[1] != null ? ((Number) row[1]).longValue() : 0L
                ));
    }

    private AdminUserStatsDTO.SessionMetrics getSessionMetrics() {
        long activeSessions = userSessionRepository.countActiveSessions();

        Map<String, Long> byDeviceType = convertGroupByData(userSessionRepository.findActiveSessionsGroupByDeviceType());
        Map<String, Long> byOs = convertGroupByData(userSessionRepository.findActiveSessionsGroupByOs());
        Map<String, Long> byBrowser = convertGroupByData(userSessionRepository.findActiveSessionsGroupByBrowser());

        Long avgDuration = userSessionRepository.findAverageSessionDurationSeconds();

        return AdminUserStatsDTO.SessionMetrics.builder()
                .activeSessions(activeSessions)
                .byDeviceType(byDeviceType)
                .byOs(byOs)
                .byBrowser(byBrowser)
                .avgSessionDurationSeconds(avgDuration != null ? avgDuration : 0L)
                .build();
    }

    private Map<String, Long> convertGroupByData(List<Object[]> data) {
        return data.stream()
                .collect(Collectors.toMap(
                        row -> row[0] != null ? row[0].toString() : "UNKNOWN",
                        row -> row[1] != null ? ((Number) row[1]).longValue() : 0L
                ));
    }

    private AdminUserStatsDTO.SecurityMetrics getSecurityMetrics() {
        LocalDateTime since24h = LocalDateTime.now().minusHours(24);
        LocalDate sevenDaysAgo = LocalDate.now().minusDays(7);
        LocalDate today = LocalDate.now();

        long failedLogins24h = userRepository.countFailedLoginsInPeriod(since24h);
        long lockedAccounts = userRepository.countLockedAccounts();
        long suspendedAccounts = userRepository.countSuspendedAccounts();

        Map<LocalDate, Long> failedLoginTrend = convertTrendData(
                userRepository.findFailedLoginTrendByDateRange(sevenDaysAgo, today)
        );

        return AdminUserStatsDTO.SecurityMetrics.builder()
                .failedLoginAttempts24h(failedLogins24h)
                .lockedAccounts(lockedAccounts)
                .suspendedAccounts(suspendedAccounts)
                .failedLoginTrend(failedLoginTrend)
                .build();
    }

    private AdminUserStatsDTO.ActivityMetrics getActivityMetrics() {
        LocalDate today = LocalDate.now();
        LocalDate startOfWeek = today.minusDays(today.getDayOfWeek().getValue() - 1);
        LocalDate startOfMonth = today.withDayOfMonth(1);

        long activeToday = userRepository.countActiveUsersByDateRange(today, today);
        long activeThisWeek = userRepository.countActiveUsersByDateRange(startOfWeek, today);
        long activeThisMonth = userRepository.countActiveUsersByDateRange(startOfMonth, today);

        return AdminUserStatsDTO.ActivityMetrics.builder()
                .usersActiveToday(activeToday)
                .usersActiveThisWeek(activeThisWeek)
                .usersActiveThisMonth(activeThisMonth)
                .build();
    }

    @Override
    public Page<AdminUserDetailResponse> searchUsers(UserSearchRequest request) {
        log.debug("Searching users with query: {}, role: {}, status: {}", request.getQuery(), request.getRole(), request.getStatus());

        Page<User> users = userRepository.searchUsers(
                request.getQuery(),
                request.getRole(),
                request.getStatus(),
                PageRequest.of(request.getPage(), request.getSize())
        );

        return users.map(this::convertToDetailResponse);
    }

    @Override
    public AdminUserDetailResponse getUserDetail(UUID userId) {
        log.debug("Fetching detailed information for user: {}", userId);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException("USER_NOT_FOUND", "User not found: " + userId));

        return convertToDetailResponse(user);
    }

    private AdminUserDetailResponse convertToDetailResponse(User user) {
        return AdminUserDetailResponse.builder()
                .id(user.getId())
                .fullName(user.getFirstName() + " " + user.getLastName())
                .email(user.getEmail())
                .phoneNumber(user.getPhoneNumber())
                .dateOfBirth(user.getDateOfBirth())
                .role(user.getRole())
                .status(user.getStatus())
                .authProvider(user.getAuthProvider() != null ? user.getAuthProvider().name() : "UNKNOWN")
                .emailVerified(user.getEmailVerifiedAt() != null)
                .createdAt(user.getCreatedAt())
                .lastLoginAt(user.getLastLoginAt())
                .updatedAt(user.getUpdatedAt())
                .activeSessions(buildSessionSummary(user.getId()))
                .statistics(AdminUserDetailResponse.UserStatistics.builder()
                        .totalAssignments(0L)
                        .totalPracticeSessions(0L)
                        .build())
                .build();
    }

    private AdminUserDetailResponse.SessionSummary buildSessionSummary(UUID userId) {
        List<com.fu.user.entity.UserSession> sessions = userSessionRepository.findActiveSessionsByUserId(userId, LocalDateTime.now());

        List<AdminUserDetailResponse.SessionSummary.SessionDetail> details = sessions.stream()
                .map(session -> {
                    String deviceInfo = "Unknown";
                    if (session.getDeviceInfo() != null) {
                        String deviceType = session.getDeviceInfo().getOrDefault("device_type", "unknown").toString();
                        String os = session.getDeviceInfo().getOrDefault("os", "unknown").toString();
                        String browser = session.getDeviceInfo().getOrDefault("browser", "unknown").toString();
                        deviceInfo = String.format("%s / %s / %s", deviceType, os, browser);
                    }

                    return AdminUserDetailResponse.SessionSummary.SessionDetail.builder()
                            .device(deviceInfo)
                            .ipAddress(session.getIpAddress())
                            .lastActiveAt(session.getLastActiveAt())
                            .createdAt(session.getCreatedAt())
                            .build();
                })
                .collect(Collectors.toList());

        return AdminUserDetailResponse.SessionSummary.builder()
                .count((long) sessions.size())
                .sessions(details)
                .build();
    }

    @Override
    @Transactional
    public void suspendUser(UUID userId) {
        log.info("Suspending user: {}", userId);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException("USER_NOT_FOUND", "User not found: " + userId));

        user.setStatus(UserStatus.SUSPENDED);
        user.setUpdatedAt(LocalDateTime.now());
        userRepository.save(user);

        log.info("User {} suspended successfully", userId);
    }

    @Override
    @Transactional
    public void activateUser(UUID userId) {
        log.info("Activating user: {}", userId);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException("USER_NOT_FOUND", "User not found: " + userId));

        user.setStatus(UserStatus.ACTIVE);
        user.setUpdatedAt(LocalDateTime.now());
        userRepository.save(user);

        log.info("User {} activated successfully", userId);
    }

    @Override
    @Transactional
    public void resetUserPassword(UUID userId) {
        log.info("Initiating password reset for user: {}", userId);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException("USER_NOT_FOUND", "User not found: " + userId));

        // In a real implementation, generate a reset token and send email
        // For now, just log the action
        log.info("Password reset initiated for user: {} ({})", userId, user.getEmail());
    }
}
