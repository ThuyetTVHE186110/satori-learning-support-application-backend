package com.fu.achievement.service;

import com.fu.achievement.dto.response.BadgeResponse;
import java.util.List;
import java.util.UUID;

/**
 * Service for managing user level progression and level-based achievements.
 * Handles EXP-to-level conversion, level-up tracking, and badge awards for milestones.
 */
public interface LearningLevelService {
    
    /**
     * Get user's current level based on total EXP
     * Formula: level = floor(sqrt(totalExp / 100)) + 1
     */
    int getCurrentLevel(UUID userId);

    /**
     * Get EXP required for next level
     */
    int getExpToNextLevel(UUID userId);

    /**
     * Get total EXP accumulated by user
     */
    int getTotalExp(UUID userId);

    /**
     * Get EXP progress toward next level (e.g., 45/100 EXP)
     */
    LevelProgressInfo getProgressToNextLevel(UUID userId);

    /**
     * Award EXP to user and check for level-up badges
     * Called by AchievementService when badges are earned
     * 
     * @param userId User ID
     * @param expAmount EXP to award
     * @return List of newly earned level badges if user leveled up
     */
    List<BadgeResponse> awardExpAndCheckLevelUp(UUID userId, int expAmount);

    /**
     * Calculate what level a user should be at based on total EXP
     * Formula: level = floor(sqrt(totalExp / 100)) + 1
     */
    int calculateLevelFromExp(int totalExp);

    /**
     * Calculate total EXP required to reach a specific level
     * Formula: exp = (level - 1)^2 * 100
     */
    int calculateExpForLevel(int level);

    /**
     * Data transfer object for level progress information
     */
    record LevelProgressInfo(
        int currentLevel,
        int totalExp,
        int expForCurrentLevel,
        int expForNextLevel,
        int expIntoLevel,
        int expToNextLevel,
        int percentToNextLevel
    ) {
    }
}
