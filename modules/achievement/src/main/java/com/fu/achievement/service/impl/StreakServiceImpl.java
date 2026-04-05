package com.fu.achievement.service.impl;

import com.fu.achievement.dto.response.BadgeResponse;
import com.fu.achievement.entity.StreakHistory;
import com.fu.achievement.entity.UserStats;
import com.fu.achievement.enums.BadgeType;
import com.fu.achievement.repository.StreakHistoryRepository;
import com.fu.achievement.repository.UserStatsRepository;
import com.fu.achievement.service.AchievementService;
import com.fu.achievement.service.StreakService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class StreakServiceImpl implements StreakService {

    private final UserStatsRepository userStatsRepository;
    private final StreakHistoryRepository streakHistoryRepository;
    private final AchievementService achievementService;

    @Override
    @Transactional
    public List<BadgeResponse> recordDailyActivity(UUID userId, String activityType) {
        LocalDate today = LocalDate.now();

        // Get or create user stats
        UserStats userStats = getUserStats(userId);

        // Check if user already has activity today
        boolean hasActivityToday = streakHistoryRepository.hasActivityOnDate(userId, today);
        if (hasActivityToday) {
            log.debug("User {} already has activity recorded for today", userId);
            return Collections.emptyList();
        }

        // Determine new streak count based on business rules
        int newStreakCount = calculateNewStreakCount(userStats, today);

        // Update user stats with new streak
        userStatsRepository.updateStreak(userId, newStreakCount, today);
        
        // Record today's activity in streak history
        StreakHistory streakRecord = StreakHistory.builder()
                .userId(userId)
                .streakDate(today)
                .streakCount(newStreakCount)
                .activityType(activityType)
                .freezeUsed(false)
                .build();
        streakHistoryRepository.save(streakRecord);

        log.info("User {} streak updated: new count = {}", userId, newStreakCount);

        // Check if user earned any LEARNING_STREAK badges (7, 30, 100 day milestones)
        try {
            List<BadgeResponse> earnedBadges = 
                achievementService.checkAndAwardBadges(userId, BadgeType.LEARNING_STREAK, newStreakCount);
            
            if (!earnedBadges.isEmpty()) {
                log.info("User {} earned {} streak badges", userId, earnedBadges.size());
            }
            
            return earnedBadges;
        } catch (Exception e) {
            log.error("Error checking streak badges for user: {}", userId, e);
            return Collections.emptyList();
        }
    }

    /**
     * Calculate new streak count based on business rules
     */
    private int calculateNewStreakCount(UserStats userStats, LocalDate today) {
        LocalDate lastActivityDate = userStats.getStreakLastDate();

        // No previous activity: start with 1
        if (lastActivityDate == null) {
            return 1;
        }

        long daysBetween = java.time.temporal.ChronoUnit.DAYS.between(lastActivityDate, today);

        // Same day: no change
        if (daysBetween == 0) {
            return userStats.getCurrentStreak();
        }

        // Consecutive day: increment
        if (daysBetween == 1) {
            return userStats.getCurrentStreak() + 1;
        }

        // Missed 1 day but has freeze: maintain streak and use freeze
        if (daysBetween == 2 && userStats.getStreakFreezeCount() > 0) {
            userStatsRepository.useFreeze(userStats.getUserId());
            log.info("User {} used freeze token to maintain streak", userStats.getUserId());
            return userStats.getCurrentStreak() + 1;
        }

        // Streak broken: reset to 1
        log.info("User {} streak broken after {} days, resetting to 1", userStats.getUserId(), daysBetween);
        return 1;
    }

    @Override
    @Transactional
    public UserStats getUserStats(UUID userId) {
        return userStatsRepository.findByUserId(userId)
                .orElseGet(() -> {
                    UserStats newStats = UserStats.builder()
                            .userId(userId)
                            .totalExp(0)
                            .currentLevel(1)
                            .expToNextLevel(100)
                            .coins(0)
                            .currentStreak(0)
                            .longestStreak(0)
                            .streakFreezeCount(0)
                            .build();
                    log.info("Created new UserStats record for user: {}", userId);
                    return userStatsRepository.save(newStats);
                });
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UserStats> getUserStatsInfo(UUID userId) {
        return userStatsRepository.findByUserId(userId);
    }

    @Override
    @Transactional
    public void addFreezeTokens(UUID userId, int count) {
        UserStats stats = getUserStats(userId);
        stats.setStreakFreezeCount(stats.getStreakFreezeCount() + count);
        userStatsRepository.save(stats);
        log.info("Added {} freeze tokens to user {}, new count: {}", count, userId, stats.getStreakFreezeCount());
    }

    @Override
    @Transactional(readOnly = true)
    public int getFreezeTokenCount(UUID userId) {
        Integer count = userStatsRepository.getFreezeCount(userId);
        return count != null ? count : 0;
    }

    @Override
    @Transactional
    public void resetStreak(UUID userId) {
        userStatsRepository.updateStreak(userId, 0, null);
        log.warn("User {} streak reset to 0 (admin action)", userId);
    }

    @Override
    @Transactional(readOnly = true)
    public int getCurrentLevel(UUID userId) {
        return userStatsRepository.findByUserId(userId)
                .map(UserStats::getCurrentLevel)
                .orElse(1);
    }

    @Override
    @Deprecated(since = "2.0", forRemoval = true)
    public int calculateLevelFromExp(int totalExp) {
        // DEPRECATED: Use LearningLevelService.calculateLevelFromExp() instead
        // This old formula (quadratic) has been replaced with a hybrid formula
        // in LearningLevelService for better progression curve
        log.warn("StreakService.calculateLevelFromExp() is deprecated. Use LearningLevelService instead.");
        
        if (totalExp <= 0) {
            return 1;
        }
        return (int) Math.floor(Math.sqrt(totalExp / 100.0)) + 1;
    }

    @Override
    @Transactional(readOnly = true)
    public int getCurrentStreakFromHistory(UUID userId) {
        Optional<StreakHistory> mostRecent = streakHistoryRepository.findMostRecentByUserId(userId);
        
        if (mostRecent.isEmpty()) {
            return 0;
        }
        
        StreakHistory recentRecord = mostRecent.get();
        Integer streakCount = recentRecord.getStreakCount();
        return streakCount != null ? streakCount : 0;
    }
}
