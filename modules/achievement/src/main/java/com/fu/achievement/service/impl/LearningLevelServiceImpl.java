package com.fu.achievement.service.impl;

import com.fu.achievement.dto.response.BadgeResponse;
import com.fu.achievement.entity.UserStats;
import com.fu.achievement.enums.BadgeType;
import com.fu.achievement.repository.UserStatsRepository;
import com.fu.achievement.service.AchievementService;
import com.fu.achievement.service.LearningLevelService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class LearningLevelServiceImpl implements LearningLevelService {

    private final UserStatsRepository userStatsRepository;
    private final AchievementService achievementService;

    @Override
    @Transactional(readOnly = true)
    public int getCurrentLevel(UUID userId) {
        return userStatsRepository.findByUserId(userId)
                .map(UserStats::getCurrentLevel)
                .orElse(1);
    }

    @Override
    @Transactional(readOnly = true)
    public int getExpToNextLevel(UUID userId) {
        return userStatsRepository.findByUserId(userId)
                .map(UserStats::getExpToNextLevel)
                .orElse(100);
    }

    @Override
    @Transactional(readOnly = true)
    public int getTotalExp(UUID userId) {
        return userStatsRepository.findByUserId(userId)
                .map(UserStats::getTotalExp)
                .orElse(0);
    }

    @Override
    @Transactional(readOnly = true)
    public LevelProgressInfo getProgressToNextLevel(UUID userId) {
        UserStats stats = userStatsRepository.findByUserId(userId)
                .orElseGet(() -> UserStats.builder()
                        .userId(userId)
                        .totalExp(0)
                        .currentLevel(1)
                        .expToNextLevel(100)
                        .build());

        int currentLevel = stats.getCurrentLevel();
        int totalExp = stats.getTotalExp();
        int expForCurrentLevel = calculateExpForLevel(currentLevel);
        int expForNextLevel = calculateExpForLevel(currentLevel + 1);
        int expIntoLevel = totalExp - expForCurrentLevel;
        int expToNextLevel = stats.getExpToNextLevel();
        int levelSize = expForNextLevel - expForCurrentLevel;
        int percentToNextLevel = (int) Math.round(((double) expIntoLevel / levelSize) * 100);

        return new LevelProgressInfo(
                currentLevel,
                totalExp,
                expForCurrentLevel,
                expForNextLevel,
                expIntoLevel,
                expToNextLevel,
                Math.min(percentToNextLevel, 100)
        );
    }

    @Override
    @Transactional
    public List<BadgeResponse> awardExpAndCheckLevelUp(UUID userId, int expAmount) {
        UserStats stats = userStatsRepository.findByUserId(userId)
                .orElseGet(() -> {
                    UserStats newStats = UserStats.builder()
                            .userId(userId)
                            .totalExp(0)
                            .currentLevel(1)
                            .expToNextLevel(100)
                            .build();
                    return userStatsRepository.save(newStats);
                });

        int previousLevel = stats.getCurrentLevel();
        int newTotalExp = stats.getTotalExp() + expAmount;
        int newLevel = calculateLevelFromExp(newTotalExp);

        stats.setTotalExp(newTotalExp);
        stats.setCurrentLevel(newLevel);
        stats.setExpToNextLevel(calculateExpForLevel(newLevel + 1) - newTotalExp);
        userStatsRepository.save(stats);

        log.info("User {} awarded {} EXP, total: {} (level {})", userId, expAmount, newTotalExp, newLevel);

        // Check if user leveled up
        if (newLevel > previousLevel) {
            log.info("User {} LEVEL UP: {} → {} ({}→{} total EXP)", 
                    userId, previousLevel, newLevel, stats.getTotalExp() - expAmount, newTotalExp);

            // Check for level-based badges (milestones at 10, 50, 100, etc.)
            try {
                List<BadgeResponse> earnedBadges = 
                    achievementService.checkAndAwardBadges(userId, BadgeType.LEARNING_LEVEL, newLevel);
                
                if (!earnedBadges.isEmpty()) {
                    log.info("User {} earned {} level-based badges at level {}", userId, earnedBadges.size(), newLevel);
                }
                
                return earnedBadges;
            } catch (Exception e) {
                log.error("Error checking level badges for user: {}", userId, e);
                return Collections.emptyList();
            }
        }

        return Collections.emptyList();
    }

    @Override
    public int calculateLevelFromExp(int totalExp) {
        // Hybrid formula: Linear for early levels, then square root for late levels
        // Configuration:
        // - THRESHOLD_EXP = 500: Switch point from linear to root progression
        // - INITIAL_DIVISOR = 40: Early levels progress quickly
        // - LATE_DIVISOR = 18.5: Late levels require exponentially more EXP
        // - LEVEL_AT_THRESHOLD = 13: Level when reaching threshold
        //
        // Progression examples:
        // Level 1: 0 EXP
        // Level 5: 160 EXP (4 days @ 40 EXP/day)
        // Level 10: 360 EXP (9 days @ 40 EXP/day)
        // Level 13: 500 EXP (12.5 days - threshold)
        // Level 20: 1,405 EXP (35 days)
        // Level 30: 3,755 EXP (94 days)
        // Level 50: 13,105 EXP (327 days ~1 year)
        
        if (totalExp <= 0) {
            return 1;
        }
        
        final int THRESHOLD_EXP = 500;
        final int INITIAL_DIVISOR = 40;
        final double LATE_DIVISOR = 18.5;
        final int LEVEL_AT_THRESHOLD = 13;
        
        if (totalExp < THRESHOLD_EXP) {
            // Early phase: Linear progression (fast)
            return (totalExp / INITIAL_DIVISOR) + 1;
        } else {
            // Late phase: Root progression (curved difficulty)
            double additionalLevels = Math.sqrt((totalExp - THRESHOLD_EXP) / LATE_DIVISOR);
            return LEVEL_AT_THRESHOLD + (int) Math.floor(additionalLevels);
        }
    }

    @Override
    public int calculateExpForLevel(int level) {
        // Inverse formula: exp = (level - 1)^2 * 100
        // This is the total EXP needed to reach the given level
        if (level <= 1) {
            return 0;
        }
        return (level - 1) * (level - 1) * 100;
    }
}
