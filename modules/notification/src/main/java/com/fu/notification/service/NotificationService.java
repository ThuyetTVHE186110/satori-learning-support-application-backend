package com.fu.notification.service;

import com.fu.notification.dto.NotificationResponse;
import com.fu.notification.dto.SendNotificationRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.UUID;

/**
 * Core service for sending and managing notifications.
 */
public interface NotificationService {

    /**
     * Send a notification based on the request.
     * Handles single user, multiple users, and topic-based notifications.
     *
     * @param request the notification request
     * @return number of notifications sent successfully
     */
    int send(SendNotificationRequest request);

    /**
     * Send a notification to a single user.
     *
     * @param userId   the target user ID
     * @param request  the notification request
     * @return true if sent successfully
     */
    boolean sendToUser(UUID userId, SendNotificationRequest request);

    /**
     * Send a notification to multiple users.
     *
     * @param userIds the target user IDs
     * @param request the notification request
     * @return number of notifications sent successfully
     */
    int sendToUsers(List<UUID> userIds, SendNotificationRequest request);

    /**
     * Send a notification to a topic.
     *
     * @param topic   the topic name
     * @param request the notification request
     * @return true if sent successfully
     */
    boolean sendToTopic(String topic, SendNotificationRequest request);

    /**
     * Get notifications for a user.
     *
     * @param userId   the user ID
     * @param pageable pagination parameters
     * @return page of notifications
     */
    Page<NotificationResponse> getNotifications(UUID userId, Pageable pageable);

    /**
     * Get unread notifications for a user.
     *
     * @param userId   the user ID
     * @param pageable pagination parameters
     * @return page of unread notifications
     */
    Page<NotificationResponse> getUnreadNotifications(UUID userId, Pageable pageable);

    /**
     * Get unread notification count for a user.
     *
     * @param userId the user ID
     * @return count of unread notifications
     */
    long getUnreadCount(UUID userId);

    /**
     * Mark specific notifications as read.
     *
     * @param userId          the user ID
     * @param notificationIds the notification IDs to mark as read
     * @return number of notifications marked as read
     */
    int markAsRead(UUID userId, List<UUID> notificationIds);

    /**
     * Mark all notifications as read for a user.
     *
     * @param userId the user ID
     * @return number of notifications marked as read
     */
    int markAllAsRead(UUID userId);

    /**
     * Delete a notification.
     *
     * @param userId         the user ID
     * @param notificationId the notification ID
     */
    void deleteNotification(UUID userId, UUID notificationId);
}
