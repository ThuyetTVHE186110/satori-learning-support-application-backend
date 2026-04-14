package com.fu.notification.repository;

import com.fu.notification.entity.Notification;
import com.fu.notification.entity.NotificationType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    /**
     * Find all notifications for a user, ordered by creation date descending.
     */
    Page<Notification> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    /**
     * Find unread notifications for a user.
     */
    Page<Notification> findByUserIdAndIsReadFalseOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    /**
     * Count unread notifications for a user.
     */
    long countByUserIdAndIsReadFalse(UUID userId);

    /**
     * Mark all notifications as read for a user.
     */
    @Modifying
    @Query("UPDATE Notification n SET n.isRead = true, n.readAt = :now WHERE n.userId = :userId AND n.isRead = false")
    int markAllAsReadForUser(@Param("userId") UUID userId, @Param("now") LocalDateTime now);

    /**
     * Mark specific notifications as read.
     */
    @Modifying
    @Query("UPDATE Notification n SET n.isRead = true, n.readAt = :now WHERE n.id IN :ids AND n.userId = :userId")
    int markAsRead(@Param("ids") List<UUID> ids, @Param("userId") UUID userId, @Param("now") LocalDateTime now);

    /**
     * Delete old notifications (cleanup).
     */
    @Modifying
    @Query("DELETE FROM Notification n WHERE n.createdAt < :cutoffDate")
    int deleteOlderThan(@Param("cutoffDate") LocalDateTime cutoffDate);

    /**
     * Delete read notifications older than specified date.
     */
    @Modifying
    @Query("DELETE FROM Notification n WHERE n.isRead = true AND n.readAt < :cutoffDate")
    int deleteReadOlderThan(@Param("cutoffDate") LocalDateTime cutoffDate);

    /**
     * Find recent notifications by type (for deduplication).
     * Native query required: JPQL binds enum as character varying which PostgreSQL
     * cannot implicitly cast to the custom notification_type ENUM type.
     */
    @Query(value = "SELECT * FROM notification.notifications n WHERE n.user_id = :userId AND n.type = cast(:type AS notification_type) AND n.created_at > :since",
           nativeQuery = true)
    List<Notification> findRecentByUserAndType(
            @Param("userId") UUID userId,
            @Param("type") String type,
            @Param("since") LocalDateTime since
    );
}
