package com.fu.achievement.controller;

import com.fu.achievement.dto.response.UserAchievementResponse;
import com.fu.achievement.dto.response.UserAchievementProgressDTO;
import com.fu.achievement.service.AchievementService;
import com.fu.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
}
