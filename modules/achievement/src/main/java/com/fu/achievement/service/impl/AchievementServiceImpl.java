package com.fu.achievement.service.impl;

import com.fu.achievement.dto.response.BadgeResponse;
import com.fu.achievement.dto.response.LearnerBadgeResponse;
import com.fu.achievement.dto.response.LearnerBadgeDetailResponse;
import com.fu.achievement.dto.response.BadgeUnlockedConditions;
import com.fu.achievement.dto.response.BadgeLearnerProgress;
import com.fu.achievement.dto.response.BadgeMilestoneInfo;
import com.fu.achievement.dto.response.BadgeStatistics;
import com.fu.achievement.dto.response.UserAchievementResponse;
import com.fu.achievement.dto.response.UserAchievementProgressDTO;
import com.fu.achievement.dto.response.CurrentStreakResponse;
import com.fu.achievement.dto.response.StreakHistoryResponse;
import com.fu.achievement.dto.response.StreakPeriodInfo;
import com.fu.achievement.dto.response.StreakSummary;
import com.fu.achievement.dto.response.StreakHistoryItem;
import com.fu.achievement.entity.Badge;
import com.fu.achievement.entity.StreakHistory;
import com.fu.achievement.entity.UserBadges;
import com.fu.achievement.enums.BadgeType;
import com.fu.achievement.mapper.BadgeMapper;
import com.fu.achievement.repository.BadgeRepository;
import com.fu.achievement.repository.StreakHistoryRepository;
import com.fu.achievement.repository.UserBadgesRepository;
import com.fu.achievement.repository.UserStatsRepository;
import com.fu.achievement.service.AchievementService;
import com.fu.achievement.service.ActivityCountProvider;
import com.fu.achievement.service.LearningLevelService;
import com.fu.achievement.service.StreakService;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
@Transactional
public class AchievementServiceImpl implements AchievementService {
    
    BadgeRepository badgeRepository;
    UserBadgesRepository userBadgesRepository;
    StreakHistoryRepository streakHistoryRepository;
    BadgeMapper badgeMapper;
    LearningLevelService learningLevelService;
    UserStatsRepository userStatsRepository;
    List<ActivityCountProvider> activityCountProviders;
    org.springframework.beans.factory.ObjectProvider<StreakService> streakServiceProvider;
    
    public AchievementServiceImpl(
            BadgeRepository badgeRepository,
            UserBadgesRepository userBadgesRepository,
            StreakHistoryRepository streakHistoryRepository,
            BadgeMapper badgeMapper,
            LearningLevelService learningLevelService,
            UserStatsRepository userStatsRepository,
            org.springframework.beans.factory.ObjectProvider<StreakService> streakServiceProvider,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            List<ActivityCountProvider> activityCountProviders) {
        this.badgeRepository = badgeRepository;
        this.userBadgesRepository = userBadgesRepository;
        this.streakHistoryRepository = streakHistoryRepository;
        this.badgeMapper = badgeMapper;
        this.learningLevelService = learningLevelService;
        this.userStatsRepository = userStatsRepository;
        this.streakServiceProvider = streakServiceProvider;
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
                    
                    // Collect EXP reward
                    int badgeExp = badge.getExpReward() != null ? badge.getExpReward() : 0;
                    totalExpFromBadges += badgeExp;
                    
                    log.info("User {} earned badge: {} ({}) with {} EXP", userId, badge.getName(), badge.getId(), badgeExp);
                }
            }
        }
        
        allBadges.addAll(earnedBadges);
        
        // Step 2 - Award EXP from badges and check for level-up badges
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
        
        // Fallback: If currentStreak is 0, check streak_history (source of truth)
        if (userStats.getCurrentStreak() == 0) {
            StreakService streakService = streakServiceProvider.getIfAvailable();
            if (streakService != null) {
                int streakFromHistory = streakService.getCurrentStreakFromHistory(userId);
                if (streakFromHistory > 0) {
                    log.warn("User {} has currentStreak=0 in UserStats but streak_history shows: {}. Using history value.",
                            userId, streakFromHistory);
                    userStats.setCurrentStreak(streakFromHistory);
                }
            }
        }
        
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
                    .badgeType(badge.getBadgeType())
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

    @Override
    @Transactional(readOnly = true)
    public Page<LearnerBadgeResponse> getAllBadgesWithProgress(UUID userId, Pageable pageable, BadgeType badgeType, String sort) {
        log.debug("Fetching all badges with progress for user: {}, type: {}, sort: {}", userId, badgeType, sort);
        
        // Get user stats for calculating current values
        com.fu.achievement.entity.UserStats userStats = userStatsRepository.findByUserId(userId)
                .orElse(com.fu.achievement.entity.UserStats.builder()
                        .userId(userId)
                        .currentStreak(0)
                        .longestStreak(0)
                        .currentLevel(1)
                        .totalExp(0)
                        .build());
        
        // Get practice and speaking counts
        int practiceCount = activityCountProviders.stream()
                .mapToInt(provider -> provider.getCompletedPracticeCount(userId))
                .sum();
        
        int speakingCount = activityCountProviders.stream()
                .mapToInt(provider -> provider.getCompletedSpeakingCount(userId))
                .sum();
        
        // Get badges with pagination
        Page<Badge> badgesPage = badgeType != null
                ? badgeRepository.findAllActiveBadgesByType(badgeType, pageable)
                : badgeRepository.findAllActiveBadges(pageable);
        
        // Convert to learner responses
        List<LearnerBadgeResponse> responses = badgesPage.getContent().stream()
                .map(badge -> buildLearnerBadgeResponse(badge, userId, userStats, practiceCount, speakingCount))
                .collect(Collectors.toList());
        
        return new PageImpl<>(responses, pageable, badgesPage.getTotalElements());
    }

    @Override
    @Transactional(readOnly = true)
    public LearnerBadgeDetailResponse getBadgeDetailWithProgress(UUID userId, UUID badgeId) {
        log.debug("Fetching badge detail for badgeId: {} and user: {}", badgeId, userId);
        
        // Get badge - if not found, return null (will be handled in controller)
        Badge badge = badgeRepository.findById(badgeId)
                .orElse(null);
        
        if (badge == null) {
            log.warn("Badge not found: {}", badgeId);
            return null;
        }
        
        // Get user stats
        com.fu.achievement.entity.UserStats userStats = userStatsRepository.findByUserId(userId)
                .orElse(com.fu.achievement.entity.UserStats.builder()
                        .userId(userId)
                        .currentStreak(0)
                        .longestStreak(0)
                        .currentLevel(1)
                        .totalExp(0)
                        .build());
        
        // Get practice and speaking counts
        int practiceCount = activityCountProviders.stream()
                .mapToInt(provider -> provider.getCompletedPracticeCount(userId))
                .sum();
        
        int speakingCount = activityCountProviders.stream()
                .mapToInt(provider -> provider.getCompletedSpeakingCount(userId))
                .sum();
        
        // Calculate current value for this badge
        int currentValue = calculateCurrentValueForBadge(badge.getBadgeType(), userStats, practiceCount, speakingCount);
        
        // Check if earned
        boolean earned = userBadgesRepository.existsByUserIdAndBadgeId(userId, badge.getId());
        LocalDateTime earnedAt = null;
        if (earned) {
            earnedAt = userBadgesRepository.findByUserId(userId).stream()
                    .filter(ub -> ub.getBadgeId().equals(badgeId))
                    .map(UserBadges::getEaredAt)
                    .findFirst()
                    .orElse(null);
        }
        
        // Build unlocking conditions
        BadgeUnlockedConditions unlockedConditions = buildBadgeUnlockedConditions(badge);
        
        // Build learner progress
        int remainingValue = Math.max(0, badge.getRequirementValue() - currentValue);
        int progressPercent = currentValue >= badge.getRequirementValue() ? 100 
                : (int) ((double) currentValue / badge.getRequirementValue() * 100);
        
        BadgeMilestoneInfo nextMilestone = findNextMilestone(badge);
        
        BadgeLearnerProgress learnerProgress = BadgeLearnerProgress.builder()
                .earned(earned)
                .earnedAt(earnedAt)
                .currentValue(currentValue)
                .remainingValue(remainingValue)
                .progressPercent(progressPercent)
                .isFeatured(earned ? userBadgesRepository.findByUserId(userId).stream()
                        .filter(ub -> ub.getBadgeId().equals(badgeId))
                        .findFirst()
                        .map(UserBadges::getIsFeatured)
                        .orElse(false) : false)
                .nextMilestone(nextMilestone)
                .build();
        
        // Build statistics
        Long totalEarners = userBadgesRepository.countByBadgeId(badge.getId());
        BadgeStatistics statistics = buildBadgeStatistics(badge, totalEarners);
        
        // Build and return full response
        return LearnerBadgeDetailResponse.builder()
                .badgeId(badge.getId())
                .name(badge.getName())
                .description(badge.getDescription())
                .badgeType(badge.getBadgeType())
                .requirementValue(badge.getRequirementValue())
                .expReward(badge.getExpReward())
                .iconUrl(badge.getIconUrl())
                .status(badge.getStatus())
                .createdAt(badge.getCreatedAt())
                .unlockedConditions(unlockedConditions)
                .learnerProgress(learnerProgress)
                .statistics(statistics)
                .build();
    }

    private LearnerBadgeResponse buildLearnerBadgeResponse(Badge badge, UUID userId, 
            com.fu.achievement.entity.UserStats userStats, int practiceCount, int speakingCount) {
        int currentValue = calculateCurrentValueForBadge(badge.getBadgeType(), userStats, practiceCount, speakingCount);
        boolean earned = userBadgesRepository.existsByUserIdAndBadgeId(userId, badge.getId());
        
        LocalDateTime earnedAt = null;
        Boolean isFeatured = false;
        if (earned) {
            var userBadge = userBadgesRepository.findByUserId(userId).stream()
                    .filter(ub -> ub.getBadgeId().equals(badge.getId()))
                    .findFirst()
                    .orElse(null);
            if (userBadge != null) {
                earnedAt = userBadge.getEaredAt();
                isFeatured = userBadge.getIsFeatured();
            }
        }
        
        int progressPercent = currentValue >= badge.getRequirementValue() ? 100
                : (int) ((double) currentValue / badge.getRequirementValue() * 100);
        
        return LearnerBadgeResponse.builder()
                .badgeId(badge.getId())
                .name(badge.getName())
                .description(badge.getDescription())
                .badgeType(badge.getBadgeType())
                .requirementValue(badge.getRequirementValue())
                .expReward(badge.getExpReward())
                .iconUrl(badge.getIconUrl())
                .earned(earned)
                .earnedAt(earnedAt)
                .currentValue(currentValue)
                .progressPercent(progressPercent)
                .isFeatured(isFeatured)
                .build();
    }

    private int calculateCurrentValueForBadge(BadgeType badgeType, 
            com.fu.achievement.entity.UserStats userStats, int practiceCount, int speakingCount) {
        switch (badgeType) {
            case LEARNING_STREAK:
                return userStats.getCurrentStreak();
            case AI_PRACTICE_COUNT:
                return practiceCount;
            case AI_SPEAKING_COUNT:
                return speakingCount;
            case LEARNING_LEVEL:
                return userStats.getCurrentLevel();
            default:
                return 0;
        }
    }

    private BadgeUnlockedConditions buildBadgeUnlockedConditions(Badge badge) {
        String description;
        String requirementUnit;
        
        switch (badge.getBadgeType()) {
            case LEARNING_STREAK:
                requirementUnit = "days";
                description = "Maintain a " + badge.getRequirementValue() + "-day learning streak";
                break;
            case AI_PRACTICE_COUNT:
                requirementUnit = "practice sessions completed";
                description = "Complete " + badge.getRequirementValue() + " AI practice sessions";
                break;
            case AI_SPEAKING_COUNT:
                requirementUnit = "speaking sessions completed";
                description = "Complete " + badge.getRequirementValue() + " AI speaking/roleplay sessions";
                break;
            case LEARNING_LEVEL:
                requirementUnit = "level";
                description = "Reach learning level " + badge.getRequirementValue();
                break;
            default:
                requirementUnit = "units";
                description = "Achieve " + badge.getRequirementValue() + " of requirement";
        }
        
        return BadgeUnlockedConditions.builder()
                .description(description)
                .requirementValue(badge.getRequirementValue())
                .requirementUnit(requirementUnit)
                .build();
    }

    private BadgeMilestoneInfo findNextMilestone(Badge badge) {
        // Get all active badges of the same type, sorted by requirement value
        List<Badge> sameBadgeTypeBadges = badgeRepository.findActiveBadgesByType(badge.getBadgeType());
        
        // Find the next milestone with higher requirement value
        return sameBadgeTypeBadges.stream()
                .filter(b -> b.getRequirementValue() > badge.getRequirementValue())
                .findFirst()
                .map(b -> BadgeMilestoneInfo.builder()
                        .badgeId(b.getId())
                        .name(b.getName())
                        .requirementValue(b.getRequirementValue())
                        .expReward(b.getExpReward())
                        .build())
                .orElse(null);
    }

    private BadgeStatistics buildBadgeStatistics(Badge badge, Long totalEarners) {
        // Get total number of users in system
        // For now, count from achievements table - users with at least one badge entry
        long totalUsersWithStats = userStatsRepository.count();
        if (totalUsersWithStats == 0) {
            totalUsersWithStats = 1; // Prevent division by zero
        }
        
        double percentage = totalEarners > 0 ? (double) totalEarners / totalUsersWithStats * 100 : 0;

        return BadgeStatistics.builder()
                .totalEarners(totalEarners.intValue())
                .totalLearners((int) totalUsersWithStats)
                .percentageEarned(Math.round(percentage * 100.0) / 100.0) // Round to 2 decimals
                .build();
    }


    @Override
    @Transactional(readOnly = true)
    public CurrentStreakResponse getCurrentStreakStatus(UUID userId) {
        log.debug("Fetching current streak status for user: {}", userId);
        
        var userStatsOpt = userStatsRepository.findByUserId(userId);
        
        // If user has no stats yet (new user), return default zeros
        if (userStatsOpt.isEmpty()) {
            log.debug("No UserStats found for user {}. Returning default zeros.", userId);
            return CurrentStreakResponse.builder()
                    .currentStreak(0)
                    .longestStreak(0)
                    .streakLastDate(null)
                    .freezeTokensAvailable(0)
                    .daysUntilBreak(0)
                    .build();
        }
        
        var userStats = userStatsOpt.get();
        
        // Fallback: If currentStreak is 0, check streak_history (source of truth)
        Integer currentStreak = userStats.getCurrentStreak();
        if (currentStreak == null || currentStreak == 0) {
            StreakService streakService = streakServiceProvider.getIfAvailable();
            if (streakService != null) {
                int streakFromHistory = streakService.getCurrentStreakFromHistory(userId);
                if (streakFromHistory > 0) {
                    log.warn("User {} has currentStreak=0 in UserStats but streak_history shows: {}. Using history value.",
                            userId, streakFromHistory);
                    currentStreak = streakFromHistory;
                }
            }
        }
        
        // Calculate days until streak breaks
        // If last activity was today, user can miss 1 day (tomorrow)
        // If last activity was yesterday or before, streak can break today
        LocalDate today = LocalDate.now();
        LocalDate lastDate = userStats.getStreakLastDate();
        int daysUntilBreak;
        
        if (lastDate == null) {
            daysUntilBreak = 0; // No streak yet
        } else if (lastDate.equals(today)) {
            daysUntilBreak = 1; // Activity today, can miss tomorrow
        } else {
            long daysSince = java.time.temporal.ChronoUnit.DAYS.between(lastDate, today);
            if (daysSince < 0) {
                // Future date - data integrity issue (likely clock skew or manipulation)
                log.warn("User {} has future streakLastDate: {}. This indicates a potential data integrity issue.", userId, lastDate);
                daysUntilBreak = 0;
            } else if (daysSince == 1) {
                daysUntilBreak = 1; // Activity yesterday, can miss today (but will break tomorrow if no activity)
            } else {
                daysUntilBreak = 0; // Streak already broken
            }
        }
        
        return CurrentStreakResponse.builder()
                .currentStreak(currentStreak != null ? currentStreak : 0)
                .longestStreak(userStats.getLongestStreak() != null ? userStats.getLongestStreak() : 0)
                .streakLastDate(lastDate)
                .freezeTokensAvailable(userStats.getStreakFreezeCount() != null ? userStats.getStreakFreezeCount() : 0)
                .daysUntilBreak(daysUntilBreak)
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public StreakHistoryResponse getStreakHistory(UUID userId, LocalDate startDate, LocalDate endDate) {
        log.debug("Fetching streak history for user: {} from {} to {}", userId, startDate, endDate);
        
        // Validate date range (startDate must be before or equal to endDate)
        if (startDate.isAfter(endDate)) {
            throw new IllegalArgumentException("startDate cannot be after endDate");
        }
        
        // Validate date range (max 90 days)
        long daysBetween = java.time.temporal.ChronoUnit.DAYS.between(startDate, endDate);
        if (daysBetween > 90) {
            log.warn("Requested date range exceeds 90 days limit, clamping to 90 days");
            startDate = endDate.minusDays(90);
        }
        
        // Query streak history for date range
        List<StreakHistory> records = streakHistoryRepository.findStreaksByDateRange(userId, startDate, endDate);
        
        // Create map for quick lookup
        Map<LocalDate, StreakHistory> recordMap = new HashMap<>();
        for (StreakHistory record : records) {
            recordMap.put(record.getStreakDate(), record);
        }
        
        // Build daily records (fill gaps for days with no activity)
        List<StreakHistoryItem> dailyRecords = new ArrayList<>();
        LocalDate current = endDate;
        
        while (!current.isBefore(startDate)) {
            StreakHistory record = recordMap.get(current);
            
            if (record != null) {
                // Day has activity
                dailyRecords.add(StreakHistoryItem.builder()
                        .date(current)
                        .streakCount(record.getStreakCount())
                        .hadActivity(true)
                        .activityType(record.getActivityType())
                        .expEarned(record.getExpEarned() != null ? record.getExpEarned() : 0)
                        .freezeUsed(record.getFreezeUsed() != null ? record.getFreezeUsed() : false)
                        .build());
            } else {
                // Day has no activity - create placeholder
                dailyRecords.add(StreakHistoryItem.builder()
                        .date(current)
                        .streakCount(0)
                        .hadActivity(false)
                        .activityType(null)
                        .expEarned(0)
                        .freezeUsed(false)
                        .build());
            }
            
            current = current.minusDays(1);
        }
        
        // Calculate summary statistics
        int activeCount = (int) dailyRecords.stream()
                .filter(StreakHistoryItem::getHadActivity)
                .count();
        int totalDays = dailyRecords.size();
        int percentage = totalDays > 0 ? Math.round((float) activeCount / totalDays * 100) : 0;
        
        // Current streak within period (consecutive days from most recent with activity going backward)
        int currentStreakInPeriod = 0;
        for (StreakHistoryItem item : dailyRecords) {
            if (item.getHadActivity()) {
                currentStreakInPeriod = item.getStreakCount();
                break;
            }
        }
        
        StreakSummary summary = StreakSummary.builder()
                .activeCount(activeCount)
                .totalDays(totalDays)
                .activityPercentage(percentage)
                .currentStreak(currentStreakInPeriod)
                .build();
        
        StreakPeriodInfo period = StreakPeriodInfo.builder()
                .startDate(startDate)
                .endDate(endDate)
                .daysRequested(totalDays)
                .build();
        
        return StreakHistoryResponse.builder()
                .period(period)
                .summary(summary)
                .dailyRecords(dailyRecords)
                .build();
    }
}
