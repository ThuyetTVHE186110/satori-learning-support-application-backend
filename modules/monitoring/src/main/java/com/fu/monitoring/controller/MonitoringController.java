package com.fu.monitoring.controller;

import com.fu.common.response.ApiResponse;
import com.fu.monitoring.dto.AdminUserStatsDTO;
import com.fu.monitoring.dto.DashboardStatsResponse;
import com.fu.monitoring.dto.OpenAiUsageResponse;
import com.fu.monitoring.service.AdminUserService;
import com.fu.monitoring.service.OpenAiUsageService;
import com.fu.monitoring.service.SystemMonitoringService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;


@RestController
@RequestMapping("/api/v1/monitoring")
@RequiredArgsConstructor
@Tag(name = "Monitoring", description = "System Monitoring and Dashboard APIs")
public class MonitoringController {

    private final SystemMonitoringService monitoringService;
    private final OpenAiUsageService openAiUsageService;
    private final AdminUserService adminUserService;

    @GetMapping("/dashboard")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Get system dashboard statistics", description = "Returns system health, usage statistics, and performance metrics for Admin dashboard.")
    public ApiResponse<DashboardStatsResponse> getDashboardStats() {
        return ApiResponse.success(monitoringService.getDashboardStats());
    }

    @GetMapping("/openai/usage")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Get OpenAI usage statistics", description = "Returns usage statistics for OpenAI resources.")
    public ApiResponse<OpenAiUsageResponse> getOpenAiUsage() {
        return ApiResponse.success(openAiUsageService.getUsage());
    }

    @GetMapping("/users/stats")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Get admin user statistics", description = "Returns comprehensive user statistics including role breakdown, new registrations, sessions, security metrics, and activity metrics.")
    public ApiResponse<AdminUserStatsDTO> getUserStatistics() {
        return ApiResponse.success(adminUserService.getUserStatistics());
    }

}
