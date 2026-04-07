package com.fu.achievement.service.impl;

import com.fu.achievement.dto.response.StreakHistoryResponse;
import com.fu.achievement.entity.StreakHistory;
import com.fu.achievement.entity.UserStats;
import com.fu.achievement.repository.StreakHistoryRepository;
import com.fu.achievement.repository.UserStatsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StreakHistoryServiceTest {

    @Mock
    private StreakHistoryRepository streakHistoryRepository;

    @Mock
    private UserStatsRepository userStatsRepository;

    @InjectMocks
    private AchievementServiceImpl achievementService;

    private UUID userId;
    private LocalDate today;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        today = LocalDate.now();
    }

    /**
     * Test: Streak of 7 days with TODAY having activity
     * Expected: streakStartDate = today - 6 = 7 days ago
     * Example: today = 2026-04-07, currentStreak = 7
     *          streakStartDate = 2026-04-01
     * Cycle 1: daysSinceStart = 6, so cycle 1 spans from 2026-04-01 to 2026-04-07 (but we query 2026-04-01 to 2026-04-07)
     */
    @Test
    void testStreakWith7DaysAndActivityToday() {
        // Setup
        LocalDate streakStart = today.minusDays(6); // 2026-04-01
        UserStats userStats = UserStats.builder()
                .currentStreak(7)
                .build();

        // Mock user stats
        when(userStatsRepository.findByUserId(userId)).thenReturn(Optional.of(userStats));

        // Mock today's record (has activity)
        StreakHistory todayRecord = StreakHistory.builder()
                .userId(userId)
                .streakDate(today)
                .streakCount(7)
                .build();
        when(streakHistoryRepository.findByUserIdAndDate(userId, today))
                .thenReturn(Optional.of(todayRecord));

        // Calculate cycle dates for cycle 1
        long daysSinceStart = 6; // today - (7 - 1) = 6 days difference
        long cycle = (daysSinceStart / 7) + 1; // (6 / 7) + 1 = 1
        long cycleStartIndex = ((cycle - 1) * 7) + 1; // ((1-1) * 7) + 1 = 1
        LocalDate cycleStartDate = streakStart.plusDays(cycleStartIndex - 1); // 2026-04-01 + 0 = 2026-04-01
        LocalDate cycleEndDate = cycleStartDate.plusDays(6); // 2026-04-01 + 6 = 2026-04-07

        // Mock 7-day range query
        List<StreakHistory> cycleRecords = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            LocalDate date = streakStart.plusDays(i);
            cycleRecords.add(StreakHistory.builder()
                    .userId(userId)
                    .streakDate(date)
                    .streakCount(i + 1)
                    .activityType("DAILY_LEARNING")
                    .expEarned(50 + i * 10)
                    .freezeUsed(false)
                    .build());
        }
        when(streakHistoryRepository.findStreaksByDateRange(eq(userId), eq(cycleStartDate), eq(cycleEndDate)))
                .thenReturn(cycleRecords);

        // Execute
        StreakHistoryResponse response = achievementService.getStreakHistory(userId);

        // Assert
        assertNotNull(response);
        assertEquals(7, response.getSummary().getCurrentStreak());
        assertEquals(streakStart, response.getPeriod().getStreakStartDate());
        assertEquals(1L, response.getPeriod().getCycleNumber());
        assertEquals(cycleStartDate, response.getPeriod().getStartDate());
        assertEquals(cycleEndDate, response.getPeriod().getEndDate());
        assertEquals(7, response.getDailyRecords().size());

        // Check that isToday is set correctly
        var todayRecord_response = response.getDailyRecords().stream()
                .filter(r -> r.getDate().equals(today))
                .findFirst();
        assertTrue(todayRecord_response.isPresent());
        assertTrue(todayRecord_response.get().getIsToday());
    }

    /**
     * Test: Streak of 7 days WITHOUT activity today (streak ended yesterday)
     * Expected: streakStartDate = today - 7 = 8 days ago
     * Example: today = 2026-04-07, currentStreak = 7 (but last activity = 2026-04-06)
     *          streakStartDate = 2026-03-31
     * Cycle 1: daysSinceStart = 7, so cycle 1 spans from 2026-03-31 to 2026-04-06 (query spans today+0 to today+6)
     */
    @Test
    void testStreakWith7DaysNoActivityToday() {
        // Setup
        LocalDate streakStart = today.minusDays(7); // 2026-03-31
        LocalDate streakEnd = today.minusDays(1);   // 2026-04-06
        UserStats userStats = UserStats.builder()
                .currentStreak(7)
                .build();

        // Mock user stats
        when(userStatsRepository.findByUserId(userId)).thenReturn(Optional.of(userStats));

        // Mock today's record (NO activity)
        when(streakHistoryRepository.findByUserIdAndDate(userId, today))
                .thenReturn(Optional.empty());

        // Calculate cycle dates for cycle 1
        long daysSinceStart = 7; // today - 7 = 7 days difference
        long cycle = (daysSinceStart / 7) + 1; // (7 / 7) + 1 = 2
        long cycleStartIndex = ((cycle - 1) * 7) + 1; // ((2-1) * 7) + 1 = 8
        LocalDate cycleStartDate = streakStart.plusDays(cycleStartIndex - 1); // 2026-03-31 + 7 = 2026-04-07
        LocalDate cycleEndDate = cycleStartDate.plusDays(6); // 2026-04-07 + 6 = 2026-04-13

        // Mock 7-day range query
        List<StreakHistory> cycleRecords = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            LocalDate date = streakStart.plusDays(i);
            cycleRecords.add(StreakHistory.builder()
                    .userId(userId)
                    .streakDate(date)
                    .streakCount(i + 1)
                    .activityType("DAILY_LEARNING")
                    .expEarned(50 + i * 10)
                    .freezeUsed(false)
                    .build());
        }
        when(streakHistoryRepository.findStreaksByDateRange(eq(userId), eq(cycleStartDate), eq(cycleEndDate)))
                .thenReturn(cycleRecords);

        // Execute
        StreakHistoryResponse response = achievementService.getStreakHistory(userId);

        // Assert
        assertNotNull(response);
        assertEquals(7, response.getSummary().getCurrentStreak());
        assertEquals(streakStart, response.getPeriod().getStreakStartDate());
        assertEquals(2L, response.getPeriod().getCycleNumber());
        assertEquals(cycleStartDate, response.getPeriod().getStartDate());
        assertEquals(cycleEndDate, response.getPeriod().getEndDate());
        assertEquals(7, response.getDailyRecords().size());

        // Check that today's record shows no activity and isToday = true
        var todayRecord_response = response.getDailyRecords().stream()
                .filter(r -> r.getDate().equals(today))
                .findFirst();
        assertTrue(todayRecord_response.isPresent());
        assertTrue(todayRecord_response.get().getIsToday());
        assertFalse(todayRecord_response.get().getHadActivity());
    }

    /**
     * Test: Streak of 1 day with activity today
     * Expected: streakStartDate = today (first day of streak)
     * Cycle 1: daysSinceStart = 0, so cycle 1 spans from today to today+6
     */
    @Test
    void testStreakWith1DayAndActivityToday() {
        // Setup
        UserStats userStats = UserStats.builder()
                .currentStreak(1)
                .build();

        when(userStatsRepository.findByUserId(userId)).thenReturn(Optional.of(userStats));

        // Mock today's record (has activity)
        StreakHistory todayRecord = StreakHistory.builder()
                .userId(userId)
                .streakDate(today)
                .streakCount(1)
                .build();
        when(streakHistoryRepository.findByUserIdAndDate(userId, today))
                .thenReturn(Optional.of(todayRecord));

        // Calculate cycle dates for cycle 1
        long daysSinceStart = 0; // today - (1 - 1) = 0 days difference
        long cycle = (daysSinceStart / 7) + 1; // (0 / 7) + 1 = 1
        long cycleStartIndex = ((cycle - 1) * 7) + 1; // ((1-1) * 7) + 1 = 1
        LocalDate cycleStartDate = today.plusDays(cycleStartIndex - 1); // today + 0 = today
        LocalDate cycleEndDate = cycleStartDate.plusDays(6); // today + 6

        // Mock 7-day range query (returns only today's record, fill rest as empty)
        List<StreakHistory> cycleRecords = List.of(todayRecord);
        when(streakHistoryRepository.findStreaksByDateRange(eq(userId), eq(cycleStartDate), eq(cycleEndDate)))
                .thenReturn(cycleRecords);

        // Execute
        StreakHistoryResponse response = achievementService.getStreakHistory(userId);

        // Assert
        assertNotNull(response);
        assertEquals(1, response.getSummary().getCurrentStreak());
        assertEquals(today, response.getPeriod().getStreakStartDate());
        assertEquals(7, response.getDailyRecords().size()); // 7 days in cycle
    }

    /**
     * Test: No streak (currentStreak = 0)
     * Expected: Empty response
     */
    @Test
    void testNoStreak() {
        // Setup
        UserStats userStats = UserStats.builder()
                .currentStreak(0)
                .build();

        when(userStatsRepository.findByUserId(userId)).thenReturn(Optional.of(userStats));

        // Execute
        StreakHistoryResponse response = achievementService.getStreakHistory(userId);

        // Assert
        assertNotNull(response);
        assertEquals(0, response.getSummary().getCurrentStreak());
        assertNull(response.getPeriod().getStreakStartDate());
        assertTrue(response.getDailyRecords().isEmpty());
    }
}
