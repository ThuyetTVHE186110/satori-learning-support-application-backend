package com.fu.achievement.service.impl;

import com.fu.achievement.dto.response.BadgeResponse;
import com.fu.achievement.dto.response.UserAchievementResponse;
import com.fu.achievement.dto.response.UserAchievementProgressDTO;
import com.fu.achievement.entity.Badge;
import com.fu.achievement.entity.UserBadges;
import com.fu.achievement.enums.BadgeType;
import com.fu.achievement.mapper.BadgeMapper;
import com.fu.achievement.repository.BadgeRepository;
import com.fu.achievement.repository.UserBadgesRepository;
import com.fu.achievement.repository.UserStatsRepository;
import com.fu.achievement.service.AchievementService;
import com.fu.achievement.service.ActivityCountProvider;
import com.fu.achievement.service.LearningLevelService;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
@Transactional
public class AchievementServiceImpl implements AchievementService {
    
    BadgeRepository badgeRepository;
    UserBadgesRepository userBadgesRepository;
    BadgeMapper badgeMapper;
    LearningLevelService learningLevelService;
    UserStatsRepository userStatsRepository;
    List<ActivityCountProvider> activityCountProviders;
    
    public AchievementServiceImpl(
            BadgeRepository badgeRepository,
            UserBadgesRepository userBadgesRepository,
            BadgeMapper badgeMapper,
            LearningLevelService learningLevelService,
            UserStatsRepository userStatsRepository,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            List<ActivityCountProvider> activityCountProviders) {
        this.badgeRepository = badgeRepository;
        this.userBadgesRepository = userBadgesRepository;
        this.badgeMapper = badgeMapper;
        this.learningLevelService = learningLevelService;
        this.userStatsRepository = userStatsRepository;
        this.activityCountProviders = activityCountProviders != null ? activityCountProviders : List.of();
    }
    
    @Override
    public List<BadgeResponse> checkAndAwardBadges(UUID userId, BadgeType badgeType, int currentValue) {
        log.debug("Checking badges for user: {} with type: {} and value: {}", userId, badgeType, currentValue);
        
        List<BadgeResponse> earnedBadges = new ArrayList<>();
        List<BadgeResponse> allBadges = new ArrayList<>();
        
        // Get all active badges of this type
        List<Badge> badgesOfType = badgeRepository.findActiveBadgesByType(badgeType);
        
        // Step 1: Award badges and collect EXP from them
        int totalExpFromBadges = 0;
        for (Badge badge : badgesOfType) {
            // Check if user qualifies for this badge
            if (currentValue >= badge.getRequirementValue()) {
                // Check if user hasn't already earned it
                if (!userBadgesRepository.existsByUserIdAndBadgeId(userId, badge.getId())) {
                    // Award the badge
                    UserBadges userBadge = UserBadges.builder()
                            .userId(userId)
                            .badgeId(badge.getId())
                            .earedAt(LocalDateTime.now())
                            .isFeatured(false)
                            .build();
                    
                    userBadgesRepository.save(userBadge);
                    
                    // Convert to response and add to list
                    BadgeResponse response = badgeMapper.toResponse(badge);
                    earnedBadges.add(response);
                    
                    // 🔑 NEW Phase 5: Collect EXP reward
                    int badgeExp = badge.getExpReward() != null ? badge.getExpReward() : 0;
                    totalExpFromBadges += badgeExp;
                    
                    log.info("User {} earned badge: {} ({}) with {} EXP", userId, badge.getName(), badge.getId(), badgeExp);
                }
            }
        }
        
        allBadges.addAll(earnedBadges);
        
        // Step 2: 🔑 NEW Phase 5 - Award EXP from badges and check for level-up badges
        if (totalExpFromBadges > 0) {
            try {
                List<BadgeResponse> levelUpBadges = 
                    learningLevelService.awardExpAndCheckLevelUp(userId, totalExpFromBadges);
                allBadges.addAll(levelUpBadges);
                
                if (!levelUpBadges.isEmpty()) {
                    log.info("User {} awarded {} EXP from badges, earned {} level-up badges", 
                             userId, totalExpFromBadges, levelUpBadges.size());
                } else {
                    log.info("User {} awarded {} EXP from badges", userId, totalExpFromBadges);
                }
            } catch (Exception e) {
                log.error("Error awarding EXP to user: {} for badge: {}", userId, badgeType, e);
                // Still return earned badges, but log the error
            }
        }
        
        return allBadges;
    }
    
    @Override
    public boolean hasUserEarnedBadge(UUID userId, UUID badgeId) {
        return userBadgesRepository.existsByUserIdAndBadgeId(userId, badgeId);
    }
    
    @Override
    @Transactional(readOnly = true)
    public List<UserAchievementResponse> getUserEarnedBadges(UUID userId) {
        log.debug("Fetching earned badges for user: {}", userId);
        
        List<UserBadges> userBadges = userBadgesRepository.findByUserId(userId);
        
        return userBadges.stream()
                .map(ub -> {
                    Badge badge = badgeRepository.findById(ub.getBadgeId()).orElse(null);
                    if (badge != null) {
                        return UserAchievementResponse.builder()
                                .badgeId(badge.getId())
                                .badgeName(badge.getName())
                                .description(badge.getDescription())
                                .badgeType(badge.getBadgeType())
                                .iconUrl(badge.getIconUrl())
                                .expReward(badge.getExpReward())
                                .earnedAt(ub.getEaredAt())
                                .isFeatured(ub.getIsFeatured())
                                .build();
                    }
                    return null;
                })
                .filter(response -> response != null)
                .collect(Collectors.toList());
    }
    
    @Override
    @Transactional(readOnly = true)
    public UserAchievementProgressDTO getUserProgress(UUID userId) {
        log.debug("Fetching achievement progress for user: {}", userId);
        
        // Get user stats
        com.fu.achievement.entity.UserStats userStats = userStatsRepository.findByUserId(userId)
                .orElse(com.fu.achievement.entity.UserStats.builder()
                        .userId(userId)
                        .currentStreak(0)
                        .longestStreak(0)
                        .currentLevel(1)
                        .totalExp(0)
                        .build());
        
        // Get practice and speaking counts from registered providers
        int practiceCount = activityCountProviders.stream()
                .mapToInt(provider -> provider.getCompletedPracticeCount(userId))
                .sum();
        
        int speakingCount = activityCountProviders.stream()
                .mapToInt(provider -> provider.getCompletedSpeakingCount(userId))
                .sum();
        
        log.debug("Retrieved counts for user {}: practice={}, speaking={}", userId, practiceCount, speakingCount);
        
        // Get all badges and calculate progress
        List<Badge> allBadges = badgeRepository.findAll();
        List<UserAchievementProgressDTO.BadgeProgressItem> badgeProgress = new ArrayList<>();
        
        for (Badge badge : allBadges) {
            // Check if user has earned this badge
            boolean earned = userBadgesRepository.existsByUserIdAndBadgeId(userId, badge.getId());
            
            // Calculate current value based on badge type
            int currentValue = 0;
            switch (badge.getBadgeType()) {
                case LEARNING_STREAK:
                    currentValue = userStats.getCurrentStreak();
                    break;
                case AI_PRACTICE_COUNT:
                    currentValue = practiceCount;
                    break;
                case AI_SPEAKING_COUNT:
                    currentValue = speakingCount;
                    break;
                case LEARNING_LEVEL:
                    currentValue = userStats.getCurrentLevel();
                    break;
            }
            
            badgeProgress.add(UserAchievementProgressDTO.BadgeProgressItem.builder()
                    .badgeId(badge.getId())
                    .badgeName(badge.getName())
                    .requirementValue(badge.getRequirementValue())
                    .currentValue(currentValue)
                    .earned(earned)
                    .build());
        }
        
        return UserAchievementProgressDTO.builder()
                .userId(userId)
                .streakCurrent(userStats.getCurrentStreak())
                .streakLongest(userStats.getLongestStreak())
                .practiceCount(practiceCount)
                .speakingCount(speakingCount)
                .currentLevel(userStats.getCurrentLevel())
                .totalExp(userStats.getTotalExp())
                .badgeProgress(badgeProgress)
                .build();
    }
}
