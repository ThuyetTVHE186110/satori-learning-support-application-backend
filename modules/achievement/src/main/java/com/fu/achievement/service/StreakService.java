package com.fu.achievement.service;

import com.fu.achievement.dto.response.BadgeResponse;
import com.fu.achievement.entity.UserStats;
import java.util.List;
import java.util.UUID;
import java.util.Optional;

/**
 * Service for managing user streaks and daily activity tracking.
 * Handles streak calculations, badge awards for streaks, and stat maintenance.
 */
public interface StreakService {
    
    /**
     * Record user activity for today and calculate streak.
     * 
     * Business Rules:
     * - Same day: No change to current streak
     * - Consecutive day: Increment streak by 1
     * - Missed 1 day with freeze: Maintain streak + consume 1 freeze token
     * - Missed >1 day or no freeze: Reset streak to 1
     * 
     * @param userId User ID
     * @param activityType Type of activity (PRACTICE, SPEAKING, ASSIGNMENT, etc.)
     * @return List of newly earned LEARNING_STREAK badges (if any)
     */
    List<BadgeResponse> recordDailyActivity(UUID userId, String activityType);

    /**
     * Get or create user stats record
     */
    UserStats getUserStats(UUID userId);

    /**
     * Get user's current streak information
     */
    Optional<UserStats> getUserStatsInfo(UUID userId);

    /**
     * Add freeze tokens to user (for in-app purchase or rewards)
     */
    void addFreezeTokens(UUID userId, int count);

    /**
     * Get remaining freeze tokens for user
     */
    int getFreezeTokenCount(UUID userId);

    /**
     * Reset user streak to 0 (admin function, e.g., for violation)
     */
    void resetStreak(UUID userId);

    /**
     * Get user's current level based on total EXP
     */
    int getCurrentLevel(UUID userId);

    /**
     * Calculate what level a user should be at based on their total EXP
     * Formula: level = floor(sqrt(totalExp / 100)) + 1
     */
    int calculateLevelFromExp(int totalExp);
}
