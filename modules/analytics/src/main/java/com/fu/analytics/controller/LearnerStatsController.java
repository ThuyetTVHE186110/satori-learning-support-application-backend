package com.fu.analytics.controller;

import com.fu.analytics.dto.*;
import com.fu.analytics.insight.LearnerStatsService;
import com.fu.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * REST Controller for learner analytics dashboard.
 * Provides data for radar charts, activity heatmaps, weekly progress, and lesson mastery.
 */
@RestController
@RequestMapping("/api/v1/learner/stats")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class LearnerStatsController {

    private final LearnerStatsService statsService;

    @GetMapping("/skill-radar")
    public ResponseEntity<ApiResponse<SkillRadarResponse>> getSkillRadar(
            @AuthenticationPrincipal UserDetails userDetails) {
        UUID userId = extractUserId(userDetails);
        return ResponseEntity.ok(ApiResponse.success(statsService.getSkillRadar(userId)));
    }

    @GetMapping("/skill-radar/speaking")
    public ResponseEntity<ApiResponse<SkillRadarResponse>> getSpeakingSkillRadar(
            @AuthenticationPrincipal UserDetails userDetails) {
        UUID userId = extractUserId(userDetails);
        return ResponseEntity.ok(ApiResponse.success(statsService.getSpeakingSkillRadar(userId)));
    }

    @GetMapping("/skill-radar/practice")
    public ResponseEntity<ApiResponse<SkillRadarResponse>> getPracticeSkillRadar(
            @AuthenticationPrincipal UserDetails userDetails) {
        UUID userId = extractUserId(userDetails);
        return ResponseEntity.ok(ApiResponse.success(statsService.getPracticeSkillRadar(userId)));
    }

    @GetMapping("/activity-heatmap")
    public ResponseEntity<ApiResponse<ActivityHeatmapResponse>> getActivityHeatmap(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestParam(defaultValue = "6") int months) {
        UUID userId = extractUserId(userDetails);
        return ResponseEntity.ok(ApiResponse.success(statsService.getActivityHeatmap(userId, months)));
    }

    @GetMapping("/progress")
    public ResponseEntity<ApiResponse<WeeklyProgressResponse>> getWeeklyProgress(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestParam(defaultValue = "8") int weeks) {
        UUID userId = extractUserId(userDetails);
        return ResponseEntity.ok(ApiResponse.success(statsService.getWeeklyProgress(userId, weeks)));
    }

    @GetMapping("/lesson-mastery")
    public ResponseEntity<ApiResponse<LessonMasteryResponse>> getLessonMastery(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestParam UUID courseId) {
        UUID userId = extractUserId(userDetails);
        return ResponseEntity.ok(ApiResponse.success(statsService.getLessonMastery(userId, courseId)));
    }

    @GetMapping("/insights")
    public ResponseEntity<ApiResponse<LearningInsightsResponse>> getInsights(
            @AuthenticationPrincipal UserDetails userDetails) {
        UUID userId = extractUserId(userDetails);
        return ResponseEntity.ok(ApiResponse.success(statsService.getInsights(userId)));
    }

    private UUID extractUserId(UserDetails userDetails) {
        return UUID.fromString(userDetails.getUsername());
    }
}
