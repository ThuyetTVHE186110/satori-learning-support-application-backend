package com.fu.monitoring.service.impl;

import com.fu.assignment.repository.AssignmentRepository;
import com.fu.common.exception.BusinessException;
import com.fu.monitoring.dto.AdminUserStatsDTO;
import com.fu.user.entity.AuthProvider;
import com.fu.user.entity.UserRole;
import com.fu.user.entity.UserStatus;
import com.fu.user.repository.UserRepository;
import com.fu.user.repository.UserSessionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.Date;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminUserServiceImplTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserSessionRepository userSessionRepository;

    @Mock
    private AssignmentRepository assignmentRepository;

    @InjectMocks
    private AdminUserServiceImpl adminUserService;

    @Test
    void getUserStatistics_shouldAggregateMetricsAndConvertTrendDates() {
        LocalDate today = LocalDate.now();

        when(userRepository.countByRole(UserRole.LEARNER)).thenReturn(10L);
        when(userRepository.countByRole(UserRole.TEACHER)).thenReturn(3L);
        when(userRepository.countByRole(UserRole.CONTENT_MANAGER)).thenReturn(2L);
        when(userRepository.countByRole(UserRole.OPERATION_MANAGER)).thenReturn(1L);
        when(userRepository.countByRole(UserRole.ADMIN)).thenReturn(1L);

        when(userRepository.countByStatus(UserStatus.ACTIVE)).thenReturn(9L);
        when(userRepository.countByStatus(UserStatus.PENDING_ACTIVATION)).thenReturn(4L);
        when(userRepository.countByStatus(UserStatus.SUSPENDED)).thenReturn(2L, 5L);
        when(userRepository.countByStatus(UserStatus.INACTIVE)).thenReturn(2L);

        when(userRepository.countByAuthProvider(AuthProvider.LOCAL)).thenReturn(14L);
        when(userRepository.countByAuthProvider(AuthProvider.GOOGLE)).thenReturn(3L);
        when(userRepository.findGenderDistribution()).thenReturn(Arrays.asList(
                new Object[]{"MALE", 8L},
                new Object[]{null, 1L}
        ));

        when(userRepository.countNewUsersByDateRange(any(), any())).thenReturn(2L, 8L, 12L);
        when(userRepository.findNewUserTrendByDateRange(any(LocalDate.class), any(LocalDate.class))).thenReturn(Arrays.asList(
                new Object[]{today, 4L},
                new Object[]{Date.valueOf(today.minusDays(1)), 3L},
                new Object[]{LocalDateTime.of(today.minusDays(2), java.time.LocalTime.NOON), 2L}
        ));

        when(userSessionRepository.countActiveSessions()).thenReturn(7L);
        when(userSessionRepository.findActiveSessionsGroupByDeviceType()).thenReturn(Arrays.asList(
                new Object[]{"MOBILE", 4L},
                new Object[]{null, 1L}
        ));
        when(userSessionRepository.findActiveSessionsGroupByOs()).thenReturn(Collections.singletonList(new Object[]{"Android", 3L}));
        when(userSessionRepository.findActiveSessionsGroupByBrowser()).thenReturn(Collections.singletonList(new Object[]{"Chrome", 5L}));
        when(userSessionRepository.findAverageSessionDurationSeconds()).thenReturn(null);

        when(userRepository.countFailedLoginsInPeriod(any())).thenReturn(6L);
        when(userRepository.countLockedAccounts()).thenReturn(1L);
        when(userRepository.findFailedLoginTrendByDateRange(any(LocalDate.class), any(LocalDate.class))).thenReturn(
                Collections.singletonList(new Object[]{Date.valueOf(today.minusDays(1)), 2L})
        );

        when(userRepository.countActiveUsersByDateRange(any(), any())).thenReturn(11L, 21L, 31L);

        AdminUserStatsDTO result = adminUserService.getUserStatistics();

        assertThat(result).isNotNull();
        assertThat(result.getCountBreakdown().getTotalUsers()).isEqualTo(17L);
        assertThat(result.getCountBreakdown().getByGender()).containsEntry("UNKNOWN", 1L);
        assertThat(result.getNewUserMetrics().getToday()).isEqualTo(2L);
        assertThat(result.getNewUserMetrics().getDailyTrend()).hasSize(3);
        assertThat(result.getSessionMetrics().getAvgSessionDurationSeconds()).isZero();
        assertThat(result.getSecurityMetrics().getSuspendedAccounts()).isEqualTo(5L);
        assertThat(result.getActivityMetrics().getUsersActiveThisMonth()).isEqualTo(31L);

        verify(userRepository).findNewUserTrendByDateRange(any(LocalDate.class), any(LocalDate.class));
        verify(userRepository).findFailedLoginTrendByDateRange(any(LocalDate.class), any(LocalDate.class));
    }

    @Test
    void getUserStatistics_shouldThrowWhenTrendDateTypeIsUnsupported() {
        when(userRepository.countByRole(any())).thenReturn(1L);
        when(userRepository.countByStatus(any())).thenReturn(1L);
        when(userRepository.countByAuthProvider(any())).thenReturn(1L);
        when(userRepository.findGenderDistribution()).thenReturn(Collections.singletonList(new Object[]{"MALE", 1L}));
        when(userRepository.countNewUsersByDateRange(any(), any())).thenReturn(1L);
        when(userRepository.findNewUserTrendByDateRange(any(LocalDate.class), any(LocalDate.class))).thenReturn(
                Collections.singletonList(new Object[]{Map.of("invalid", "type"), 1L})
        );

        assertThatThrownBy(() -> adminUserService.getUserStatistics())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported trend date type");
    }

    @Test
    void getUserStatistics_shouldThrowWhenTrendContainsDuplicateDates() {
        LocalDate today = LocalDate.now();

        when(userRepository.countByRole(any())).thenReturn(1L);
        when(userRepository.countByStatus(any())).thenReturn(1L);
        when(userRepository.countByAuthProvider(any())).thenReturn(1L);
        when(userRepository.findGenderDistribution()).thenReturn(Collections.singletonList(new Object[]{"MALE", 1L}));
        when(userRepository.countNewUsersByDateRange(any(), any())).thenReturn(1L, 1L, 1L);
        when(userRepository.findNewUserTrendByDateRange(any(LocalDate.class), any(LocalDate.class))).thenReturn(Arrays.asList(
                new Object[]{today, 1L},
                new Object[]{Date.valueOf(today), 2L}
        ));

        assertThatThrownBy(() -> adminUserService.getUserStatistics())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate key");
    }

    @Test
    void getUserStatistics_shouldMapUnknownKeysAndNullCountsToDefaults() {
        LocalDate today = LocalDate.now();

        when(userRepository.countByRole(UserRole.LEARNER)).thenReturn(1L);
        when(userRepository.countByRole(UserRole.TEACHER)).thenReturn(1L);
        when(userRepository.countByRole(UserRole.CONTENT_MANAGER)).thenReturn(1L);
        when(userRepository.countByRole(UserRole.OPERATION_MANAGER)).thenReturn(1L);
        when(userRepository.countByRole(UserRole.ADMIN)).thenReturn(1L);

        when(userRepository.countByStatus(UserStatus.ACTIVE)).thenReturn(1L);
        when(userRepository.countByStatus(UserStatus.PENDING_ACTIVATION)).thenReturn(1L);
        when(userRepository.countByStatus(UserStatus.SUSPENDED)).thenReturn(1L, 1L);
        when(userRepository.countByStatus(UserStatus.INACTIVE)).thenReturn(1L);

        when(userRepository.countByAuthProvider(AuthProvider.LOCAL)).thenReturn(3L);
        when(userRepository.countByAuthProvider(AuthProvider.GOOGLE)).thenReturn(2L);

        when(userRepository.findGenderDistribution()).thenReturn(Collections.singletonList(new Object[]{null, null}));
        when(userRepository.countNewUsersByDateRange(any(), any())).thenReturn(1L, 1L, 1L);
        when(userRepository.findNewUserTrendByDateRange(any(LocalDate.class), any(LocalDate.class))).thenReturn(
                Collections.singletonList(new Object[]{LocalDateTime.of(today, LocalTime.NOON), null})
        );

        when(userSessionRepository.countActiveSessions()).thenReturn(2L);
        when(userSessionRepository.findActiveSessionsGroupByDeviceType()).thenReturn(
                Collections.singletonList(new Object[]{null, null})
        );
        when(userSessionRepository.findActiveSessionsGroupByOs()).thenReturn(
                Collections.singletonList(new Object[]{null, 2L})
        );
        when(userSessionRepository.findActiveSessionsGroupByBrowser()).thenReturn(
                Collections.singletonList(new Object[]{"Chrome", null})
        );
        when(userSessionRepository.findAverageSessionDurationSeconds()).thenReturn(120L);

        when(userRepository.countFailedLoginsInPeriod(any())).thenReturn(0L);
        when(userRepository.countLockedAccounts()).thenReturn(0L);
        when(userRepository.findFailedLoginTrendByDateRange(any(LocalDate.class), any(LocalDate.class))).thenReturn(
                Collections.singletonList(new Object[]{Date.valueOf(today), null})
        );
        when(userRepository.countActiveUsersByDateRange(any(), any())).thenReturn(0L, 0L, 0L);

        AdminUserStatsDTO result = adminUserService.getUserStatistics();

        assertThat(result.getCountBreakdown().getByGender()).containsEntry("UNKNOWN", 0L);
        assertThat(result.getSessionMetrics().getByDeviceType()).containsEntry("UNKNOWN", 0L);
        assertThat(result.getSessionMetrics().getByOs()).containsEntry("UNKNOWN", 2L);
        assertThat(result.getSessionMetrics().getByBrowser()).containsEntry("Chrome", 0L);
        assertThat(result.getNewUserMetrics().getDailyTrend()).containsValue(0L);
        assertThat(result.getSecurityMetrics().getFailedLoginTrend()).containsValue(0L);
        assertThat(result.getSessionMetrics().getAvgSessionDurationSeconds()).isEqualTo(120L);
    }

    @Test
    void searchUsers_shouldReturnEmptyPageWithNormalizedPaging() {
        var page = adminUserService.searchUsers(com.fu.monitoring.dto.UserSearchRequest.builder()
                .page(-1)
                .size(1000)
                .build());

        assertThat(page.getContent()).isEmpty();
        assertThat(page.getNumber()).isZero();
        assertThat(page.getSize()).isEqualTo(20);
    }

    @Test
    void getUserDetail_shouldThrowNotImplementedBusinessException() {
        assertThatThrownBy(() -> adminUserService.getUserDetail(java.util.UUID.randomUUID()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("not implemented");
    }
}
