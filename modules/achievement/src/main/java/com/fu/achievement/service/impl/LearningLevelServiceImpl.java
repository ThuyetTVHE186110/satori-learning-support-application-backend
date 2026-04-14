package com.fu.achievement.service.impl;

import com.fu.achievement.dto.response.BadgeResponse;
import com.fu.achievement.entity.UserStats;
import com.fu.achievement.enums.BadgeType;
import com.fu.achievement.repository.UserStatsRepository;
import com.fu.achievement.service.AchievementService;
import com.fu.achievement.service.LearningLevelService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class LearningLevelServiceImpl implements LearningLevelService {

    // ── Level formula constants (shared between calculateLevelFromExp / calculateExpForLevel) ──
    /** EXP threshold where progression switches from linear to square-root curve. */
    private static final int THRESHOLD_EXP = 500;
    /** Divisor for the early (linear) phase — each level costs this many EXP. */
    private static final int INITIAL_DIVISOR = 40;
    /** Divisor for the late (root) phase — higher = slower progression. */
    private static final double LATE_DIVISOR = 18.5;
    /** Level reached exactly at THRESHOLD_EXP. */
    private static final int LEVEL_AT_THRESHOLD = 13;

    private final UserStatsRepository userStatsRepository;
    private final ObjectProvider<AchievementService> achievementServiceProvider;


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
        
        // Enforce MAX_LEVEL cap
        if (newLevel > com.fu.achievement.config.LevelExpTierConfig.MAX_LEVEL) {
            newLevel = com.fu.achievement.config.LevelExpTierConfig.MAX_LEVEL;
            // If at max level, calculate max EXP for that level
            newTotalExp = calculateExpForLevel(com.fu.achievement.config.LevelExpTierConfig.MAX_LEVEL + 1) - 1;
            log.info("User {} reached MAX_LEVEL ({}), capping EXP at {}", 
                    userId, com.fu.achievement.config.LevelExpTierConfig.MAX_LEVEL, newTotalExp);
        }

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
                AchievementService achievementService = achievementServiceProvider.getIfAvailable();
                if (achievementService != null) {
                    List<BadgeResponse> earnedBadges = 
                        achievementService.checkAndAwardBadges(userId, BadgeType.LEARNING_LEVEL, newLevel);
                    
                    if (!earnedBadges.isEmpty()) {
                        log.info("User {} earned {} level-based badges at level {}", userId, earnedBadges.size(), newLevel);
                    }
                    
                    return earnedBadges;
                } else {
                    log.warn("AchievementService not available for level up check");
                }
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
        if (totalExp <= 0) {
            return 1;
        }

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
        if (level <= 1) {
            return 0;
        }

        if (level <= LEVEL_AT_THRESHOLD) {
            // Early phase: Inverse of linear formula
            // Forward: level = (totalExp / 40) + 1
            // Inverse: totalExp = (level - 1) * 40
            return (level - 1) * INITIAL_DIVISOR;
        } else {
            // Late phase: Inverse of root formula
            // Forward: level = 13 + floor(√((totalExp - 500) / 18.5))
            // Inverse: totalExp = 500 + ((level - 13)² × 18.5)
            int levelDiff = level - LEVEL_AT_THRESHOLD;
            return THRESHOLD_EXP + (int) Math.round(levelDiff * levelDiff * LATE_DIVISOR);
        }
    }
}
