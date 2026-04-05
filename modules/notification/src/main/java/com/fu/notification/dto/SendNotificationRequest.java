package com.fu.notification.dto;

import com.fu.notification.entity.NotificationPriority;
import com.fu.notification.entity.NotificationType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Internal DTO for sending notifications.
 * Used by services to request notification delivery.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SendNotificationRequest {

    /**
     * Target user ID (for single user notification).
     */
    private UUID userId;

    /**
     * Target user IDs (for batch notification).
     */
    private List<UUID> userIds;

    /**
     * Topic name (for topic-based notification).
     */
    private String topic;

    /**
     * Type of notification.
     */
    private NotificationType type;

    /**
     * Priority level.
     */
    @Builder.Default
    private NotificationPriority priority = NotificationPriority.NORMAL;

    /**
     * Notification title.
     */
    private String title;

    /**
     * Notification body.
     */
    private String body;

    /**
     * Additional data payload.
     */
    private Map<String, Object> data;

    /**
     * Optional image URL.
     */
    private String imageUrl;

    /**
     * Deep link action URL.
     */
    private String actionUrl;

    /**
     * Whether to save to notification history.
     */
    @Builder.Default
    private boolean saveToHistory = true;

    /**
     * Whether to skip user preference checks.
     * Use for critical system notifications.
     */
    @Builder.Default
    private boolean bypassPreferences = false;

    /**
     * Creates a request for a single user.
     */
    public static SendNotificationRequest toUser(UUID userId, NotificationType type, String title, String body) {
        return SendNotificationRequest.builder()
                .userId(userId)
                .type(type)
                .title(title)
                .body(body)
                .build();
    }

    /**
     * Creates a request for multiple users.
     */
    public static SendNotificationRequest toUsers(List<UUID> userIds, NotificationType type, String title, String body) {
        return SendNotificationRequest.builder()
                .userIds(userIds)
                .type(type)
                .title(title)
                .body(body)
                .build();
    }

    /**
     * Creates a request for a topic.
     */
    public static SendNotificationRequest toTopic(String topic, NotificationType type, String title, String body) {
        return SendNotificationRequest.builder()
                .topic(topic)
                .type(type)
                .title(title)
                .body(body)
                .build();
    }
}
