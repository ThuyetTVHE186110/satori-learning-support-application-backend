package com.fu.notification.repository;

import com.fu.notification.entity.UserNotificationPreferences;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserNotificationPreferencesRepository extends JpaRepository<UserNotificationPreferences, UUID> {

    /**
     * Find preferences by user ID.
     */
    Optional<UserNotificationPreferences> findByUserId(UUID userId);

    /**
     * Check if preferences exist for a user.
     */
    boolean existsByUserId(UUID userId);

    /**
     * Find users with daily reminders enabled at a specific time.
     * Used by the daily reminder scheduler.
     */
    @Query("""
        SELECT p.userId FROM UserNotificationPreferences p 
        WHERE p.pushEnabled = true 
        AND p.dailyReminderEnabled = true 
        AND p.dailyReminderTime = :reminderTime
        """)
    List<UUID> findUsersWithDailyReminderAt(@Param("reminderTime") LocalTime reminderTime);

    /**
     * Find preferences for multiple users.
     */
    @Query("SELECT p FROM UserNotificationPreferences p WHERE p.userId IN :userIds")
    List<UserNotificationPreferences> findByUserIds(@Param("userIds") List<UUID> userIds);
}
