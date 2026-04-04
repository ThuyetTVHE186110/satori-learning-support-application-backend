package com.fu.notification.entity;

import com.fu.infrastructure.persistence.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnTransformer;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Stores FCM device tokens for push notifications.
 * Each user can have multiple devices (web, mobile app).
 */
@Entity
@Table(name = "user_device_tokens", schema = "notification", indexes = {
        @Index(name = "idx_device_tokens_user_id", columnList = "user_id"),
        @Index(name = "idx_device_tokens_active", columnList = "is_active, last_active_at")
}, uniqueConstraints = {
        @UniqueConstraint(name = "uk_fcm_token", columnNames = "fcm_token")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@ToString(callSuper = true)
public class UserDeviceToken extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "fcm_token", nullable = false, length = 500)
    private String fcmToken;

    @Enumerated(EnumType.STRING)
    @Column(name = "device_type", nullable = false, columnDefinition = "device_type")
    @ColumnTransformer(write = "?::device_type")
    private DeviceType deviceType;

    @Column(name = "device_name", length = 100)
    private String deviceName;

    @Column(name = "app_version", length = 20)
    private String appVersion;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "last_active_at")
    private LocalDateTime lastActiveAt;

    /**
     * Updates the last active timestamp.
     */
    public void markActive() {
        this.lastActiveAt = LocalDateTime.now();
        this.isActive = true;
    }

    /**
     * Deactivates this device token (e.g., on logout or token invalidation).
     */
    public void deactivate() {
        this.isActive = false;
    }
}
