package com.fu.notification.repository;

import com.fu.notification.entity.UserDeviceToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserDeviceTokenRepository extends JpaRepository<UserDeviceToken, UUID> {

    /**
     * Find all active device tokens for a user.
     */
    List<UserDeviceToken> findByUserIdAndIsActiveTrue(UUID userId);

    /**
     * Find all active device tokens for multiple users.
     */
    @Query("SELECT t FROM UserDeviceToken t WHERE t.userId IN :userIds AND t.isActive = true")
    List<UserDeviceToken> findActiveTokensByUserIds(@Param("userIds") List<UUID> userIds);

    /**
     * Find a device token by its FCM token.
     */
    Optional<UserDeviceToken> findByFcmToken(String fcmToken);

    /**
     * Deactivate all tokens for a user (e.g., on password change).
     */
    @Modifying
    @Query("UPDATE UserDeviceToken t SET t.isActive = false WHERE t.userId = :userId")
    int deactivateAllForUser(@Param("userId") UUID userId);

    /**
     * Deactivate a specific token.
     */
    @Modifying
    @Query("UPDATE UserDeviceToken t SET t.isActive = false WHERE t.fcmToken = :fcmToken")
    int deactivateByFcmToken(@Param("fcmToken") String fcmToken);

    /**
     * Delete inactive tokens older than the specified date.
     * Used for cleanup of stale tokens.
     */
    @Modifying
    @Query("DELETE FROM UserDeviceToken t WHERE t.isActive = false AND t.updatedAt < :cutoffDate")
    int deleteInactiveTokensOlderThan(@Param("cutoffDate") LocalDateTime cutoffDate);
}
