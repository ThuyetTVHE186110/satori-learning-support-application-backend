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
     * 
     * Uses hybrid formula:
     * - Early (EXP < 500): level = floor(totalExp / 40) + 1 (linear progression)
     * - Late (EXP >= 500): level = 13 + floor(√((totalExp - 500) / 18.5)) (curved progression)
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
     * Called when activities are completed or badges are earned
     * 
     * @param userId User ID
     * @param expAmount EXP to award
     * @return List of newly earned level badges if user leveled up
     */
    List<BadgeResponse> awardExpAndCheckLevelUp(UUID userId, int expAmount);

    /**
     * Calculate what level a user should be at based on total EXP
     * 
     * Uses hybrid formula:
     * - Early (EXP < 500): level = floor(totalExp / 40) + 1 → Level 10 in ~6 days
     * - Late (EXP >= 500): level = 13 + floor(√((totalExp - 500) / 18.5)) → Level 50 in ~1 year
     * 
     * Threshold at 500 EXP = Level 13 ensures smooth transition between phases.
     */
    int calculateLevelFromExp(int totalExp);

    /**
     * Calculate total EXP required to reach a specific level
     * This is the INVERSE of calculateLevelFromExp() and MUST match exactly.
     * 
     * Inverse formula:
     * - For level <= 13: totalExp = (level - 1) * 40
     * - For level > 13: totalExp = 500 + ((level - 13)² × 18.5)
     * 
     * Example:
     * - Level 5 → 160 EXP (early phase)
     * - Level 13 → 500 EXP (threshold)
     * - Level 20 → ~1407 EXP (late phase, curved)
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
