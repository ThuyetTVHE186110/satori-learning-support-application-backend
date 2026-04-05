package com.fu.achievement.repository;

import com.fu.achievement.entity.StreakHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface StreakHistoryRepository extends JpaRepository<StreakHistory, UUID> {
    
    /**
     * Find streak history for user on a specific date
     */
    @Query("SELECT sh FROM StreakHistory sh WHERE sh.userId = :userId AND sh.streakDate = :streakDate")
    Optional<StreakHistory> findByUserIdAndDate(@Param("userId") UUID userId, @Param("streakDate") LocalDate streakDate);

    /**
     * Find the most recent streak record for a user (useful for calculating current streak)
     */
    @Query("SELECT sh FROM StreakHistory sh WHERE sh.userId = :userId ORDER BY sh.streakDate DESC LIMIT 1")
    Optional<StreakHistory> findMostRecentByUserId(@Param("userId") UUID userId);

    /**
     * Check if user had activity on a specific date
     */
    @Query("SELECT CASE WHEN COUNT(sh) > 0 THEN true ELSE false END FROM StreakHistory sh WHERE sh.userId = :userId AND sh.streakDate = :streakDate")
    boolean hasActivityOnDate(@Param("userId") UUID userId, @Param("streakDate") LocalDate streakDate);

    /**
     * Count consecutive days of activity from a given date backwards
     */
    @Query(value = """
            SELECT COUNT(*) FROM gamification.streak_history 
            WHERE user_id = :userId 
            AND streak_date >= DATE(:endDate) - INTERVAL '90 days'
            AND streak_date <= :endDate
            ORDER BY streak_date DESC
            """, nativeQuery = true)
    long countRecentDaysOfActivity(@Param("userId") UUID userId, @Param("endDate") LocalDate endDate);
}
