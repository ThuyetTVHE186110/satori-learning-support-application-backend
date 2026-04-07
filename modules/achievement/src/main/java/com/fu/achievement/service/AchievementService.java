package com.fu.achievement.service;

import com.fu.achievement.dto.response.BadgeResponse;
import com.fu.achievement.dto.response.LearnerBadgeResponse;
import com.fu.achievement.dto.response.LearnerBadgeDetailResponse;
import com.fu.achievement.dto.response.UserAchievementResponse;
import com.fu.achievement.dto.response.UserAchievementProgressDTO;
import com.fu.achievement.dto.response.CurrentStreakResponse;
import com.fu.achievement.dto.response.StreakHistoryResponse;
import com.fu.achievement.enums.BadgeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.time.LocalDate;
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

    /**
     * Get all available badges with learner's progress toward each (paginated)
     *
     * @param userId The user ID
     * @param pageable Pagination information (page, size)
     * @param badgeType Optional badge type filter
     * @param sort Sort field (name, expReward, requirementValue)
     * @return Page of available badges with learner's current progress
     */
    Page<LearnerBadgeResponse> getAllBadgesWithProgress(UUID userId, Pageable pageable, BadgeType badgeType, String sort);

    /**
     * Get detailed information about a specific badge with learner's progress
     *
     * @param userId The user ID
     * @param badgeId The badge ID to get details for
     * @return Comprehensive badge detail including unlocking conditions, learner progress, and statistics
     */
    LearnerBadgeDetailResponse getBadgeDetailWithProgress(UUID userId, UUID badgeId);

    /**
     * Get current streak status with last activity date
     *
     * @param userId The user ID
     * @return Current streak, longest streak, last date, and freeze tokens
     */
    CurrentStreakResponse getCurrentStreakStatus(UUID userId);

    /**
     * Get streak history for a date range with daily breakdown
     *
     * @param userId The user ID
     * @return Streak history with daily records, activity types, and summary statistics
     */
    StreakHistoryResponse getStreakHistory(UUID userId);
}
