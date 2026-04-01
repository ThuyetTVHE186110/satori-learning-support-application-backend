package com.fu.monitoring.controller;

import com.fu.common.response.ApiResponse;
import com.fu.monitoring.dto.DashboardStatsResponse;
import com.fu.monitoring.service.SystemMonitoringService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/monitoring")
@RequiredArgsConstructor
@Tag(name = "Monitoring", description = "System Monitoring and Dashboard APIs")
public class MonitoringController {

    private final SystemMonitoringService monitoringService;

    @GetMapping("/dashboard")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Get system dashboard statistics", description = "Returns system health, usage statistics, and performance metrics for Admin dashboard.")
    public ApiResponse<DashboardStatsResponse> getDashboardStats() {
        return ApiResponse.success(monitoringService.getDashboardStats());
    }
}
