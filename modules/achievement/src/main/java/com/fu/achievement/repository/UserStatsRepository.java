package com.fu.achievement.repository;

import com.fu.achievement.entity.UserStats;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserStatsRepository extends JpaRepository<UserStats, UUID> {
    
    /**
     * Find user stats by user ID
     */
    @Query("SELECT us FROM UserStats us WHERE us.userId = :userId")
    Optional<UserStats> findByUserId(@Param("userId") UUID userId);

    /**
     * Update current streak and related fields (atomic operation)
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE UserStats us SET 
            us.currentStreak = :newStreak,
            us.streakLastDate = :streakDate,
            us.longestStreak = GREATEST(us.longestStreak, :newStreak),
            us.updatedAt = CURRENT_TIMESTAMP
            WHERE us.userId = :userId
            """)
    void updateStreak(@Param("userId") UUID userId, @Param("newStreak") Integer newStreak, @Param("streakDate") LocalDate streakDate);

    /**
     * Use a freeze token (decrease freeze count by 1)
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE UserStats us SET 
            us.streakFreezeCount = GREATEST(0, us.streakFreezeCount - 1),
            us.updatedAt = CURRENT_TIMESTAMP
            WHERE us.userId = :userId
            """)
    void useFreeze(@Param("userId") UUID userId);

    /**
     * Add EXP to user and update level
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE UserStats us SET 
            us.totalExp = us.totalExp + :expAmount,
            us.updatedAt = CURRENT_TIMESTAMP
            WHERE us.userId = :userId
            """)
    void addExp(@Param("userId") UUID userId, @Param("expAmount") Integer expAmount);

    /**
     * Increment practice count (stored in user_stats or derived from daily_activity)
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE UserStats us SET 
            us.updatedAt = CURRENT_TIMESTAMP
            WHERE us.userId = :userId
            """)
    void touchLastUpdated(@Param("userId") UUID userId);

    /**
     * Check if user has freeze tokens available
     */
    @Query("SELECT us.streakFreezeCount > 0 FROM UserStats us WHERE us.userId = :userId")
    boolean hasFreezeAvailable(@Param("userId") UUID userId);

    /**
     * Get freeze count for user
     */
    @Query("SELECT us.streakFreezeCount FROM UserStats us WHERE us.userId = :userId")
    Integer getFreezeCount(@Param("userId") UUID userId);
}
