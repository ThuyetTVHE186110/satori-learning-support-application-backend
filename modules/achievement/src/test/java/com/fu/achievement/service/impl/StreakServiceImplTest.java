package com.fu.achievement.service.impl;

import com.fu.achievement.dto.response.BadgeResponse;
import com.fu.achievement.dto.response.StreakNotificationResponse;
import com.fu.achievement.entity.StreakHistory;
import com.fu.achievement.entity.UserStats;
import com.fu.achievement.enums.BadgeType;
import com.fu.achievement.repository.StreakHistoryRepository;
import com.fu.achievement.repository.UserStatsRepository;
import com.fu.achievement.service.AchievementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("StreakServiceImpl Tests")
class StreakServiceImplTest {

    @Mock
    private UserStatsRepository userStatsRepository;

    @Mock
    private StreakHistoryRepository streakHistoryRepository;

    @Mock
    private AchievementService achievementService;

    @InjectMocks
    private StreakServiceImpl streakService;

    private UUID userId;
    private LocalDate today;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        today = LocalDate.now();
    }

    // ==================== Streak Broken Tests ====================

    @Test
    @DisplayName("When streak is broken (>2 days gap), new streak should start with 1")
    void testStreakBrokenStartsNewStreakWith1() {
        // Setup
        LocalDate lastActivityDate = today.minusDays(3); // 3 days ago
        UserStats userStats = UserStats.builder()
                .userId(userId)
                .currentStreak(5)
                .longestStreak(10)
                .streakLastDate(lastActivityDate)
                .streakFreezeCount(0)
                .build();

        when(userStatsRepository.findByUserId(userId)).thenReturn(Optional.of(userStats));
        when(streakHistoryRepository.hasActivityOnDate(userId, today)).thenReturn(false);
        when(achievementService.checkAndAwardBadges(userId, BadgeType.LEARNING_STREAK, 1))
                .thenReturn(Collections.emptyList());

        // Execute
        StreakNotificationResponse response = streakService.recordDailyActivity(userId, "PRACTICE");

        // Assert
        assertNotNull(response);
        assertTrue(response.isFirstActivityToday(), "Should be first activity of today");
        
        // Verify updateStreak was called with newStreakCount=1
        ArgumentCaptor<Integer> streakCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(userStatsRepository).updateStreak(eq(userId), streakCaptor.capture(), eq(today));
        assertEquals(1, streakCaptor.getValue(), "Broken streak should start new streak with 1");
        
        // Verify streak history was recorded with count=1
        ArgumentCaptor<StreakHistory> historyCaptor = ArgumentCaptor.forClass(StreakHistory.class);
        verify(streakHistoryRepository).save(historyCaptor.capture());
        assertEquals(1, historyCaptor.getValue().getStreakCount());
    }

    @Test
    @DisplayName("When >2 days gap with no freeze token, streak resets and starts fresh")
    void testLargeGapWithoutFreezeResetsStreak() {
        // Setup: 5-day gap, 3 streak count, 0 freezes
        LocalDate lastActivityDate = today.minusDays(5);
        UserStats userStats = UserStats.builder()
                .userId(userId)
                .currentStreak(3)
                .longestStreak(7)
                .streakLastDate(lastActivityDate)
                .streakFreezeCount(0)
                .build();

        when(userStatsRepository.findByUserId(userId)).thenReturn(Optional.of(userStats));
        when(streakHistoryRepository.hasActivityOnDate(userId, today)).thenReturn(false);
        when(achievementService.checkAndAwardBadges(userId, BadgeType.LEARNING_STREAK, 1))
                .thenReturn(Collections.emptyList());

        // Execute
        streakService.recordDailyActivity(userId, "ASSIGNMENT");

        // Assert
        ArgumentCaptor<Integer> streakCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(userStatsRepository).updateStreak(eq(userId), streakCaptor.capture(), eq(today));
        assertEquals(1, streakCaptor.getValue());
    }

    @Test
    @DisplayName("When 2-day gap with freeze token, streak continues and freeze is consumed")
    void testTwoDayGapWithFreezeTokenContinuesStreak() {
        // Setup: 2-day gap, 5 streak count, 1 freeze available
        LocalDate lastActivityDate = today.minusDays(2);
        UserStats userStats = UserStats.builder()
                .userId(userId)
                .currentStreak(5)
                .longestStreak(10)
                .streakLastDate(lastActivityDate)
                .streakFreezeCount(1) // Has freeze token
                .build();

        when(userStatsRepository.findByUserId(userId)).thenReturn(Optional.of(userStats));
        when(streakHistoryRepository.hasActivityOnDate(userId, today)).thenReturn(false);
        when(achievementService.checkAndAwardBadges(userId, BadgeType.LEARNING_STREAK, 6))
                .thenReturn(Collections.emptyList());

        // Execute
        streakService.recordDailyActivity(userId, "PRACTICE");

        // Assert - streak should increment to 6, not reset to 1
        ArgumentCaptor<Integer> streakCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(userStatsRepository).updateStreak(eq(userId), streakCaptor.capture(), eq(today));
        assertEquals(6, streakCaptor.getValue(), "With freeze, streak should continue (5+1=6)");
        
        // Verify freeze was used
        verify(userStatsRepository).useFreeze(userId);
    }

    @Test
    @DisplayName("When consecutive day, streak increments")
    void testConsecutiveDayIncrementsStreak() {
        // Setup: 1-day gap (yesterday), 5 streak count
        LocalDate lastActivityDate = today.minusDays(1);
        UserStats userStats = UserStats.builder()
                .userId(userId)
                .currentStreak(5)
                .longestStreak(10)
                .streakLastDate(lastActivityDate)
                .streakFreezeCount(0)
                .build();

        when(userStatsRepository.findByUserId(userId)).thenReturn(Optional.of(userStats));
        when(streakHistoryRepository.hasActivityOnDate(userId, today)).thenReturn(false);
        when(achievementService.checkAndAwardBadges(userId, BadgeType.LEARNING_STREAK, 6))
                .thenReturn(Collections.emptyList());

        // Execute
        streakService.recordDailyActivity(userId, "PRACTICE");

        // Assert
        ArgumentCaptor<Integer> streakCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(userStatsRepository).updateStreak(eq(userId), streakCaptor.capture(), eq(today));
        assertEquals(6, streakCaptor.getValue(), "Consecutive day should increment (5+1=6)");
    }

    @Test
    @DisplayName("When same-day activity, streak count unchanged")
    void testSameDayActivityDoesNotChangeStreak() {
        // Setup
        UserStats userStats = UserStats.builder()
                .userId(userId)
                .currentStreak(5)
                .longestStreak(10)
                .streakLastDate(today) // Same day
                .build();

        when(userStatsRepository.findByUserId(userId)).thenReturn(Optional.of(userStats));
        when(streakHistoryRepository.hasActivityOnDate(userId, today)).thenReturn(true); // Already has activity

        // Execute
        StreakNotificationResponse response = streakService.recordDailyActivity(userId, "PRACTICE");

        // Assert
        assertFalse(response.isFirstActivityToday(), "Second activity same day");
        assertEquals(5, response.getCurrentStreak(), "Streak should remain at 5");
        
        // Verify NO update to streak
        verify(userStatsRepository, never()).updateStreak(any(), any(), any());
    }

    @Test
    @DisplayName("When no prior activity, new streak starts with 1")
    void testNoPriorActivityStartsStreakWith1() {
        // Setup: No prior activity
        UserStats userStats = UserStats.builder()
                .userId(userId)
                .currentStreak(0)
                .longestStreak(0)
                .streakLastDate(null) // No prior activity
                .build();

        when(userStatsRepository.findByUserId(userId)).thenReturn(Optional.of(userStats));
        when(streakHistoryRepository.hasActivityOnDate(userId, today)).thenReturn(false);
        when(achievementService.checkAndAwardBadges(userId, BadgeType.LEARNING_STREAK, 1))
                .thenReturn(Collections.emptyList());

        // Execute
        streakService.recordDailyActivity(userId, "PRACTICE");

        // Assert
        ArgumentCaptor<Integer> streakCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(userStatsRepository).updateStreak(eq(userId), streakCaptor.capture(), eq(today));
        assertEquals(1, streakCaptor.getValue(), "First activity should start with streak=1");
    }

    @Test
    @DisplayName("StreakHistory record saved with correct activity type")
    void testStreakHistoryRecordedWithActivityType() {
        // Setup
        UserStats userStats = UserStats.builder()
                .userId(userId)
                .currentStreak(0)
                .streakLastDate(null)
                .build();

        when(userStatsRepository.findByUserId(userId)).thenReturn(Optional.of(userStats));
        when(streakHistoryRepository.hasActivityOnDate(userId, today)).thenReturn(false);
        when(achievementService.checkAndAwardBadges(userId, BadgeType.LEARNING_STREAK, 1))
                .thenReturn(Collections.emptyList());

        // Execute
        streakService.recordDailyActivity(userId, "ASSIGNMENT");

        // Assert
        ArgumentCaptor<StreakHistory> historyCaptor = ArgumentCaptor.forClass(StreakHistory.class);
        verify(streakHistoryRepository).save(historyCaptor.capture());
        
        StreakHistory savedRecord = historyCaptor.getValue();
        assertEquals(userId, savedRecord.getUserId());
        assertEquals(today, savedRecord.getStreakDate());
        assertEquals(1, savedRecord.getStreakCount());
        assertEquals("ASSIGNMENT", savedRecord.getActivityType());
        assertFalse(savedRecord.getFreezeUsed());
    }
}
