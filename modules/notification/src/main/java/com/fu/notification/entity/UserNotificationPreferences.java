package com.fu.notification.entity;

import com.fu.infrastructure.persistence.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.time.LocalTime;
import java.util.UUID;

/**
 * User preferences for notifications.
 * Controls what types of notifications users receive and when.
 */
@Entity
@Table(name = "user_notification_preferences", schema = "notification", uniqueConstraints = {
        @UniqueConstraint(name = "uk_notification_prefs_user", columnNames = "user_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@ToString(callSuper = true)
public class UserNotificationPreferences extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    // ── Global Settings ───────────────────────────────────────────────────────

    @Column(name = "push_enabled", nullable = false)
    @Builder.Default
    private Boolean pushEnabled = true;

    @Column(name = "email_enabled", nullable = false)
    @Builder.Default
    private Boolean emailEnabled = true;

    // ── Category Settings ─────────────────────────────────────────────────────

    @Column(name = "daily_reminder_enabled", nullable = false)
    @Builder.Default
    private Boolean dailyReminderEnabled = true;

    @Column(name = "daily_reminder_time")
    @Builder.Default
    private LocalTime dailyReminderTime = LocalTime.of(8, 0);

    @Column(name = "assignment_notifications", nullable = false)
    @Builder.Default
    private Boolean assignmentNotifications = true;

    @Column(name = "achievement_notifications", nullable = false)
    @Builder.Default
    private Boolean achievementNotifications = true;

    @Column(name = "social_notifications", nullable = false)
    @Builder.Default
    private Boolean socialNotifications = true;

    @Column(name = "marketing_notifications", nullable = false)
    @Builder.Default
    private Boolean marketingNotifications = false;

    // ── Quiet Hours ───────────────────────────────────────────────────────────

    @Column(name = "quiet_hours_enabled", nullable = false)
    @Builder.Default
    private Boolean quietHoursEnabled = false;

    @Column(name = "quiet_hours_start")
    @Builder.Default
    private LocalTime quietHoursStart = LocalTime.of(22, 0);

    @Column(name = "quiet_hours_end")
    @Builder.Default
    private LocalTime quietHoursEnd = LocalTime.of(7, 0);

    /**
     * Creates default preferences for a new user.
     */
    public static UserNotificationPreferences createDefault(UUID userId) {
        return UserNotificationPreferences.builder()
                .userId(userId)
                .build();
    }

    /**
     * Checks if the current time is within quiet hours.
     */
    public boolean isQuietTime(LocalTime now) {
        if (!quietHoursEnabled) {
            return false;
        }
        
        // Handle overnight quiet hours (e.g., 22:00 - 07:00)
        if (quietHoursStart.isAfter(quietHoursEnd)) {
            return now.isAfter(quietHoursStart) || now.isBefore(quietHoursEnd);
        }
        
        return now.isAfter(quietHoursStart) && now.isBefore(quietHoursEnd);
    }

    /**
     * Checks if a notification type is enabled for this user.
     */
    public boolean isNotificationTypeEnabled(NotificationType type) {
        if (!pushEnabled) {
            return false;
        }

        return switch (type) {
            case DAILY_REMINDER, STREAK_ALERT, LEARNING_GOAL_REACHED -> dailyReminderEnabled;
            case ASSIGNMENT_CREATED, ASSIGNMENT_DUE_SOON, ASSIGNMENT_OVERDUE,
                 ASSIGNMENT_GRADED, SUBMISSION_RECEIVED -> assignmentNotifications;
            case ACHIEVEMENT_UNLOCKED, LESSON_COMPLETE, PRACTICE_FEEDBACK_READY -> achievementNotifications;
            case NEW_MESSAGE, ROLEPLAY_FEEDBACK, PRONUNCIATION_RESULT,
                 CLASS_JOINED, CLASS_REMOVED, CLASS_ANNOUNCEMENT -> socialNotifications;
            case NEW_CONTENT_AVAILABLE, SYSTEM_UPDATE -> marketingNotifications;
            // Always-on: security + import status (manager workflow critical path)
            case ACCOUNT_SECURITY, PASSWORD_CHANGED, MAINTENANCE_NOTICE, WELCOME,
                 CURRICULUM_IMPORT_PENDING_APPROVAL, CURRICULUM_IMPORT_FAILED -> true;
        };
    }
}
