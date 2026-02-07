package com.fu.user.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Entity representing user login sessions.
 * Used for session management, refresh token handling, and multi-device tracking.
 * Maps to user_management.user_sessions table.
 */
@Entity
@Table(name = "user_sessions", schema = "user_management")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserSession {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /**
     * Hashed refresh token for security.
     * The actual token is returned to client, but stored hashed in DB.
     */
    @Column(name = "refresh_token", length = 512)
    private String refreshToken;

    /**
     * Device information stored as JSON.
     * Structure: {
     *   "device_type": "mobile|desktop|tablet",
     *   "os": "iOS 17.2|Android 14|Windows 11|macOS",
     *   "browser": "Chrome|Safari|Firefox",
     *   "app_version": "2.1.0",
     *   "device_model": "iPhone 15|Samsung S24",
     *   "push_token": "fcm_token_for_notifications"
     * }
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "device_info", columnDefinition = "jsonb")
    private Map<String, Object> deviceInfo;

    /**
     * Client IP address for security tracking.
     */
    @Column(name = "ip_address", columnDefinition = "inet")
    private String ipAddress;

    /**
     * User agent string from the request.
     */
    @Column(name = "user_agent", length = 500)
    private String userAgent;

    /**
     * Session creation timestamp.
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    /**
     * Session expiration time.
     * After this time, the refresh token is no longer valid.
     */
    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    /**
     * Timestamp when this session was revoked (logged out).
     * NULL means the session is still active.
     */
    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    /**
     * Last activity timestamp for this session.
     * Updated when refresh token is used.
     */
    @Column(name = "last_active_at")
    @Builder.Default
    private LocalDateTime lastActiveAt = LocalDateTime.now();

    /**
     * Check if this session is still active (not expired and not revoked).
     */
    public boolean isActive() {
        return revokedAt == null && expiresAt.isAfter(LocalDateTime.now());
    }

    /**
     * Revoke this session (logout).
     */
    public void revoke() {
        this.revokedAt = LocalDateTime.now();
    }
}
