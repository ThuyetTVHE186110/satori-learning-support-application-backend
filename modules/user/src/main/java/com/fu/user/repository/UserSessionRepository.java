package com.fu.user.repository;

import com.fu.user.entity.UserSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for UserSession entity.
 * Manages login sessions and refresh tokens.
 */
@Repository
public interface UserSessionRepository extends JpaRepository<UserSession, UUID> {

    /**
     * Find active session by refresh token (not expired and not revoked).
     */
    @Query("SELECT s FROM UserSession s WHERE s.refreshToken = :refreshToken " +
           "AND s.revokedAt IS NULL AND s.expiresAt > :now")
    Optional<UserSession> findActiveByRefreshToken(
            @Param("refreshToken") String refreshToken,
            @Param("now") LocalDateTime now);

    /**
     * Find all active sessions for a user.
     */
    @Query("SELECT s FROM UserSession s WHERE s.user.id = :userId " +
           "AND s.revokedAt IS NULL AND s.expiresAt > :now " +
           "ORDER BY s.createdAt DESC")
    List<UserSession> findActiveSessionsByUserId(
            @Param("userId") UUID userId,
            @Param("now") LocalDateTime now);

    /**
     * Count active sessions for a user.
     */
    @Query("SELECT COUNT(s) FROM UserSession s WHERE s.user.id = :userId " +
           "AND s.revokedAt IS NULL AND s.expiresAt > :now")
    long countActiveSessionsByUserId(
            @Param("userId") UUID userId,
            @Param("now") LocalDateTime now);

    /**
     * Revoke all sessions for a user (logout from all devices).
     * Used when user changes password or requests logout from all devices.
     */
    @Modifying
    @Query("UPDATE UserSession s SET s.revokedAt = :revokedAt " +
           "WHERE s.user.id = :userId AND s.revokedAt IS NULL")
    int revokeAllSessionsByUserId(
            @Param("userId") UUID userId,
            @Param("revokedAt") LocalDateTime revokedAt);

    /**
     * Revoke all sessions for a user except the current one.
     * Used when user wants to logout from other devices.
     */
    @Modifying
    @Query("UPDATE UserSession s SET s.revokedAt = :revokedAt " +
           "WHERE s.user.id = :userId AND s.id != :currentSessionId AND s.revokedAt IS NULL")
    int revokeOtherSessionsByUserId(
            @Param("userId") UUID userId,
            @Param("currentSessionId") UUID currentSessionId,
            @Param("revokedAt") LocalDateTime revokedAt);

    /**
     * Delete expired sessions (cleanup job).
     */
    @Modifying
    @Query("DELETE FROM UserSession s WHERE s.expiresAt < :cutoffDate")
    int deleteExpiredSessions(@Param("cutoffDate") LocalDateTime cutoffDate);

    /**
     * Find session by ID and user ID.
     */
    Optional<UserSession> findByIdAndUserId(UUID id, UUID userId);
}
