package com.fu.notification.entity;

import com.fu.infrastructure.persistence.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.ColumnTransformer;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Notification history for in-app notification center.
 * Stores all notifications sent to users for display and tracking.
 */
@Entity
@Table(name = "notifications", schema = "notification", indexes = {
        @Index(name = "idx_notifications_user_unread", columnList = "user_id, is_read, created_at DESC")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@ToString(callSuper = true)
public class Notification extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, columnDefinition = "notification_type")
    @ColumnTransformer(write = "?::notification_type")
    private NotificationType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "priority", nullable = false, columnDefinition = "notification_priority")
    @ColumnTransformer(write = "?::notification_priority")
    @Builder.Default
    private NotificationPriority priority = NotificationPriority.NORMAL;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "body", columnDefinition = "TEXT")
    private String body;

    /**
     * Additional data payload (JSON).
     * Can contain: assignmentId, lessonId, conversationId, etc.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "data", columnDefinition = "jsonb")
    private Map<String, Object> data;

    @Column(name = "image_url", length = 500)
    private String imageUrl;

    /**
     * Deep link URL for navigation when notification is clicked.
     */
    @Column(name = "action_url", length = 500)
    private String actionUrl;

    @Column(name = "is_read", nullable = false)
    @Builder.Default
    private Boolean isRead = false;

    @Column(name = "read_at")
    private LocalDateTime readAt;

    /**
     * Whether the push notification was sent successfully via FCM.
     */
    @Column(name = "push_sent", nullable = false)
    @Builder.Default
    private Boolean pushSent = false;

    @Column(name = "push_sent_at")
    private LocalDateTime pushSentAt;

    /**
     * Marks the notification as read.
     */
    public void markAsRead() {
        if (!this.isRead) {
            this.isRead = true;
            this.readAt = LocalDateTime.now();
        }
    }

    /**
     * Records successful push delivery.
     */
    public void markPushSent() {
        this.pushSent = true;
        this.pushSentAt = LocalDateTime.now();
    }
}
