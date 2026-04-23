package com.fu.monitoring.controller;

import com.fu.common.exception.BusinessException;
import com.fu.common.response.ApiResponse;
import com.fu.monitoring.dto.AdminUserStatsDTO;
import com.fu.monitoring.dto.DashboardStatsResponse;
import com.fu.monitoring.dto.OpenAiUsageResponse;
import com.fu.monitoring.service.AdminUserService;
import com.fu.monitoring.service.ClassManagementDashboardService;
import com.fu.monitoring.service.OpenAiUsageService;
import com.fu.monitoring.service.SystemMonitoringService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MonitoringControllerTest {

    @Mock
    private SystemMonitoringService monitoringService;

    @Mock
    private OpenAiUsageService openAiUsageService;

    @Mock
    private AdminUserService adminUserService;
    @Mock
    private ClassManagementDashboardService dashboardService;

    private MonitoringController controller;

    @BeforeEach
    void setUp() {
        controller = new MonitoringController(monitoringService, openAiUsageService, adminUserService, dashboardService);
    }

    @Test
    void getDashboardStats_shouldReturnSuccessResponse() {
        DashboardStatsResponse payload = DashboardStatsResponse.builder().build();
        when(monitoringService.getDashboardStats()).thenReturn(payload);

        ApiResponse<DashboardStatsResponse> response = controller.getDashboardStats();

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getCode()).isEqualTo("SUCCESS");
        assertThat(response.getData()).isSameAs(payload);
    }

    @Test
    void getOpenAiUsage_shouldReturnSuccessResponse() {
        OpenAiUsageResponse payload = OpenAiUsageResponse.builder().totalUsed(100.0).build();
        when(openAiUsageService.getUsage()).thenReturn(payload);

        ApiResponse<OpenAiUsageResponse> response = controller.getOpenAiUsage();

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getCode()).isEqualTo("SUCCESS");
        assertThat(response.getData()).isSameAs(payload);
    }

    @Test
    void getUserStatistics_shouldReturnSuccessResponse() {
        AdminUserStatsDTO payload = AdminUserStatsDTO.builder().build();
        when(adminUserService.getUserStatistics()).thenReturn(payload);

        ApiResponse<AdminUserStatsDTO> response = controller.getUserStatistics();

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getCode()).isEqualTo("SUCCESS");
        assertThat(response.getData()).isSameAs(payload);
    }

    @Test
    void getDashboardStats_shouldWrapNullPayloadAsSuccess() {
        when(monitoringService.getDashboardStats()).thenReturn(null);

        ApiResponse<DashboardStatsResponse> response = controller.getDashboardStats();

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getData()).isNull();
        verify(monitoringService).getDashboardStats();
    }

    @Test
    void getOpenAiUsage_shouldPropagateBusinessException() {
        when(openAiUsageService.getUsage()).thenThrow(new BusinessException("OPENAI_USAGE_CLIENT_ERROR", "failed"));

        assertThatThrownBy(() -> controller.getOpenAiUsage())
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("failed");

        verify(openAiUsageService).getUsage();
    }

    @Test
    void getUserStatistics_shouldPropagateUnexpectedException() {
        when(adminUserService.getUserStatistics()).thenThrow(new IllegalStateException("boom"));

        assertThatThrownBy(() -> controller.getUserStatistics())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("boom");

        verify(adminUserService).getUserStatistics();
    }
}
