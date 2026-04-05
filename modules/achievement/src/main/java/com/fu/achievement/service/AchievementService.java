package com.fu.achievement.service;

import com.fu.achievement.dto.response.BadgeResponse;
import com.fu.achievement.dto.response.UserAchievementResponse;
import com.fu.achievement.dto.response.UserAchievementProgressDTO;
import com.fu.achievement.enums.BadgeType;
import java.util.List;
import java.util.UUID;

public interface AchievementService {
    /**
     * Check if user qualifies for any badges of the given type and award them
     *
     * @param userId The user ID
     * @param badgeType The type of badge to check (LEARNING_STREAK, AI_PRACTICE_COUNT, etc.)
     * @param currentValue The current value to check against badge requirements
     * @return List of newly earned badges, empty if none earned
     */
    List<BadgeResponse> checkAndAwardBadges(UUID userId, BadgeType badgeType, int currentValue);

    /**
     * Check if user has already earned a specific badge
     *
     * @param userId The user ID
     * @param badgeId The badge ID
     * @return true if user has earned this badge, false otherwise
     */
    boolean hasUserEarnedBadge(UUID userId, UUID badgeId);

    /**
     * Get all badges earned by a user
     *
     * @param userId The user ID
     * @return List of earned badges with details
     */
    List<UserAchievementResponse> getUserEarnedBadges(UUID userId);

    /**
     * Get progress information for a user toward all available badges
     *
     * @param userId The user ID
     * @return Progress data including current values and progress toward locked badges
     */
    UserAchievementProgressDTO getUserProgress(UUID userId);
}
