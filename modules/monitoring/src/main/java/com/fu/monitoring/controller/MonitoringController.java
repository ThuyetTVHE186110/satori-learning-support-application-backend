package com.fu.monitoring.controller;

import com.fu.common.response.ApiResponse;
import com.fu.monitoring.dto.AdminUserStatsDTO;
import com.fu.monitoring.dto.DashboardStatsResponse;
import com.fu.monitoring.dto.OpenAiUsageResponse;
import com.fu.monitoring.dto.response.AchievementMetricsResponse;
import com.fu.monitoring.dto.response.AdminDashboardResponse;
import com.fu.monitoring.dto.response.ClassManagementDashboardResponse;
import com.fu.monitoring.dto.response.ClassStatusChartResponse;
import com.fu.monitoring.dto.response.ContentManagerDashboardResponse;
import com.fu.monitoring.dto.response.DashboardKpiResponse;
import com.fu.monitoring.dto.response.PopularBadgesResponse;
import com.fu.monitoring.dto.response.PriorityClassesResponse;
import com.fu.monitoring.dto.response.RareBadgesResponse;
import com.fu.monitoring.dto.response.StudentTrendResponse;
import com.fu.monitoring.service.AdminDashboardService;
import com.fu.monitoring.service.AdminUserService;
import com.fu.monitoring.service.ClassManagementDashboardService;
import com.fu.monitoring.service.ContentManagerDashboardService;
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
    private final AdminDashboardService adminDashboardService;
    private final ClassManagementDashboardService dashboardService;
    private final ContentManagerDashboardService contentManagerDashboardService;

    // =====================================================
    // EXISTING ADMIN ENDPOINTS
    // =====================================================

    @GetMapping("/admin/dashboard")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Get admin overview dashboard",
               description = "Returns all widgets for the admin dashboard in one call: KPI cards, user growth trend, role distribution, system health, and AI session trend.")
    public ApiResponse<AdminDashboardResponse> getAdminDashboard() {
        return ApiResponse.success(adminDashboardService.getDashboard());
    }


    // =====================================================
    // CLASS MANAGEMENT DASHBOARD ENDPOINTS
    // =====================================================

    @GetMapping("/operation/dashboard")
    @PreAuthorize("hasAnyRole('OPERATION_MANAGER')")
    @Operation(summary = "Get complete class management dashboard",
               description = "Returns all 6 sections: KPIs, status chart, student trend, priority classes, achievements, popular badges.")
    public ApiResponse<ClassManagementDashboardResponse> getClassManagementDashboard() {
        return ApiResponse.success(dashboardService.getDashboard());
    }

    @GetMapping("/operation/metrics")
    @PreAuthorize("hasAnyRole('OPERATION_MANAGER')")
    @Operation(summary = "Get metrics summary",
               description = "Returns 4 metrics: total classes, active classes, total students, total teachers.")
    public ApiResponse<DashboardKpiResponse> getKpiSummary() {
        return ApiResponse.success(dashboardService.getKpiSummary());
    }

    @GetMapping("/operation/class-status")
    @PreAuthorize("hasAnyRole('OPERATION_MANAGER')")
    @Operation(summary = "Get class status distribution",
               description = "Returns counts for donut chart: active, closed, not started.")
    public ApiResponse<ClassStatusChartResponse> getStatusChart() {
        return ApiResponse.success(dashboardService.getStatusChart());
    }

    @GetMapping("/operation/student-trend")
    @PreAuthorize("hasAnyRole('OPERATION_MANAGER')")
    @Operation(summary = "Get student enrollment trend",
               description = "Returns monthly enrollment data for last 6 months.")
    public ApiResponse<StudentTrendResponse> getStudentTrend() {
        return ApiResponse.success(dashboardService.getStudentTrend());
    }

    @GetMapping("/operation/priority-classes")
    @PreAuthorize("hasAnyRole('OPERATION_MANAGER')")
    @Operation(summary = "Get priority classes requiring attention",
               description = "Returns top 10 classes: overdue, ending soon, starting soon, incomplete enrollment.")
    public ApiResponse<PriorityClassesResponse> getPriorityClasses() {
        return ApiResponse.success(dashboardService.getPriorityClasses());
    }

    @GetMapping("/operation/achievements")
    @PreAuthorize("hasAnyRole('OPERATION_MANAGER')")
    @Operation(summary = "Get achievement metrics",
               description = "Returns: total badges awarded, badge award rate, total EXP awarded.")
    public ApiResponse<AchievementMetricsResponse> getAchievementMetrics() {
        return ApiResponse.success(dashboardService.getAchievementMetrics());
    }

    @GetMapping("/operation/popular-badges")
    @PreAuthorize("hasAnyRole('OPERATION_MANAGER')")
    @Operation(summary = "Get top 5 popular badges",
               description = "Returns most awarded badges with award counts.")
    public ApiResponse<PopularBadgesResponse> getPopularBadges() {
        return ApiResponse.success(dashboardService.getPopularBadges());
    }

    @GetMapping("/operation/rare-badges")
    @PreAuthorize("hasAnyRole('OPERATION_MANAGER')")
    @Operation(summary = "Get top 5 rare badges — Thành tích ít người đạt",
               description = "Returns badges with the fewest earners (awardCount > 0), sorted ascending.")
    public ApiResponse<RareBadgesResponse> getRareBadges() {
        return ApiResponse.success(dashboardService.getRareBadges());
    }

    // =====================================================
    // CONTENT MANAGER ENDPOINTS
    // =====================================================

    @GetMapping("/content-manager/dashboard")
    @PreAuthorize("hasRole('CONTENT_MANAGER')")
    @Operation(summary = "Get content manager dashboard",
               description = "Returns comprehensive dashboard with content statistics, status distributions, and performance metrics for content managers.")
    public ApiResponse<ContentManagerDashboardResponse> getContentManagerDashboard() {
        return ApiResponse.success(contentManagerDashboardService.getDashboard());
    }
}
