package com.fu.achievement.controller;

import com.fu.achievement.dto.response.LearnerBadgeDetailResponse;
import com.fu.achievement.dto.response.LearnerBadgeResponse;
import com.fu.achievement.dto.response.UserAchievementResponse;
import com.fu.achievement.dto.response.UserAchievementProgressDTO;
import com.fu.achievement.dto.response.CurrentStreakResponse;
import com.fu.achievement.dto.response.StreakHistoryResponse;
import com.fu.achievement.enums.BadgeType;
import com.fu.achievement.service.AchievementService;
import com.fu.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Tag(name = "Learner Achievements", description = "Endpoints for learners to view their earned badges and progress")
@RestController
@RequestMapping("/api/v1/learner/achievements")
@PreAuthorize("hasRole('LEARNER')")
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
@RequiredArgsConstructor
public class LearnerAchievementController {
    
    private final AchievementService achievementService;
    
    /**
     * Get all badges earned by the current learner
     * 
     * @param userId Current authenticated user ID
     * @return List of earned badges with details
     */
    @GetMapping
    public ResponseEntity<ApiResponse<List<UserAchievementResponse>>> getMyBadges(
            @AuthenticationPrincipal UUID userId) {
        List<UserAchievementResponse> badges = achievementService.getUserEarnedBadges(userId);
        return ResponseEntity.ok(ApiResponse.success(badges));
    }
    
    /**
     * Get progress information toward all available badges
     * 
     * @param userId Current authenticated user ID
     * @return Progress data including current values and progress toward locked badges
     */
    @GetMapping("/progress")
    public ResponseEntity<ApiResponse<UserAchievementProgressDTO>> getMyProgress(
            @AuthenticationPrincipal UUID userId) {
        UserAchievementProgressDTO progress = achievementService.getUserProgress(userId);
        return ResponseEntity.ok(ApiResponse.success(progress));
    }
    
    /**
     * Get badges earned by the learner in the last 24 hours (for notifications)
     * 
     * @param userId Current authenticated user ID
     * @return List of recently earned badges
     */
    @GetMapping("/new")
    public ResponseEntity<ApiResponse<List<UserAchievementResponse>>> getNewBadges(
            @AuthenticationPrincipal UUID userId) {
        // Filter badges earned in last 24 hours from the full list
        List<UserAchievementResponse> allBadges = achievementService.getUserEarnedBadges(userId);
        java.time.LocalDateTime oneDayAgo = java.time.LocalDateTime.now().minusHours(24);
        
        List<UserAchievementResponse> newBadges = allBadges.stream()
                .filter(badge -> badge.getEarnedAt() != null && badge.getEarnedAt().isAfter(oneDayAgo))
                .toList();
        
        return ResponseEntity.ok(ApiResponse.success(newBadges));
    }
    /**
     * Get all available badges with learner's progress toward each
     *
     * @param userId Current authenticated user ID
     * @param page Page number (default 0)
     * @param size Page size (default 20, max 50)
     * @param type Optional badge type filter
     * @param sort Sort field (default "name")
     * @return Paginated list of badges with learner progress
     */
    @Operation(summary = "Browse all available badges",
            description = "Get all badges in the system with learner's progress toward each badge")
    @GetMapping("/badges")
    public ResponseEntity<ApiResponse<Page<LearnerBadgeResponse>>> getAllBadges(
            @AuthenticationPrincipal UUID userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) BadgeType type,
            @RequestParam(defaultValue = "name") String sort) {

        // Validate pagination parameters
        if (page < 0) page = 0;
        if (size < 1) size = 1;
        if (size > 50) size = 50;

        Pageable pageable = PageRequest.of(page, size);
        Page<LearnerBadgeResponse> badges = achievementService.getAllBadgesWithProgress(userId, pageable, type, sort);

        return ResponseEntity.ok(ApiResponse.success(badges));
    }

    /**
     * Get detailed information about a specific badge with learner's progress
     *
     * @param userId Current authenticated user ID
     * @param badgeId The badge ID to get details for
     * @return Comprehensive badge detail including unlocking conditions and learner progress
     */
    @Operation(summary = "View badge details",
            description = "Get detailed information about a specific badge including unlocking conditions and learner's progress")
    @GetMapping("/badges/{badgeId}")
    public ResponseEntity<ApiResponse<LearnerBadgeDetailResponse>> getBadgeDetail(
            @AuthenticationPrincipal UUID userId,
            @PathVariable UUID badgeId) {

        LearnerBadgeDetailResponse badgeDetail = achievementService.getBadgeDetailWithProgress(userId, badgeId);

        if (badgeDetail == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error("Badge not found", "BADGE_NOT_FOUND"));
        }

        return ResponseEntity.ok(ApiResponse.success(badgeDetail));
    }

    /**
     * Get current streak status with last activity date
     *
     * @param userId Current authenticated user ID
     * @return Current streak, longest streak, last date, and freeze tokens
     */
    @Operation(summary = "Get current streak status",
            description = "Get current learning streak with last activity date and remaining freeze tokens")
    @GetMapping("/streaks/current")
    public ResponseEntity<ApiResponse<CurrentStreakResponse>> getCurrentStreak(
            @AuthenticationPrincipal UUID userId) {
        CurrentStreakResponse streak = achievementService.getCurrentStreakStatus(userId);
        return ResponseEntity.ok(ApiResponse.success(streak));
    }

    /**
     * Get streak history for a date range with daily breakdown
     *
     * @param userId Current authenticated user ID
     * @param days Number of days to retrieve (default: 7, max: 90)
     * @return Streak history with daily records and summary statistics
     */
    @Operation(summary = "Get streak history by date range",
            description = "Get detailed streak history showing which days had activity and which didn't (last 7/30/90 days)")
    @GetMapping("/streaks/history")
    public ResponseEntity<ApiResponse<StreakHistoryResponse>> getStreakHistory(
            @AuthenticationPrincipal UUID userId,
            @RequestParam(defaultValue = "7") int days) {

        // Validate days parameter
        if (days < 1) days = 1;
        if (days > 90) days = 90;

        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(days - 1);

        StreakHistoryResponse history = achievementService.getStreakHistory(userId, startDate, endDate);
        return ResponseEntity.ok(ApiResponse.success(history));
    }
}
