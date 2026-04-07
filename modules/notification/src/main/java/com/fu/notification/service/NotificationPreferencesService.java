package com.fu.notification.service;

import com.fu.notification.dto.NotificationPreferencesResponse;
import com.fu.notification.dto.UpdateNotificationPreferencesRequest;
import com.fu.notification.entity.NotificationType;
import com.fu.notification.entity.UserNotificationPreferences;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Service for managing user notification preferences.
 */
public interface NotificationPreferencesService {

    /**
     * Get notification preferences for a user.
     * Creates default preferences if none exist.
     *
     * @param userId the user ID
     * @return the user's notification preferences
     */
    NotificationPreferencesResponse getPreferences(UUID userId);

    /**
     * Update notification preferences for a user.
     *
     * @param userId  the user ID
     * @param request the update request
     * @return the updated preferences
     */
    NotificationPreferencesResponse updatePreferences(UUID userId, UpdateNotificationPreferencesRequest request);

    /**
     * Check if a notification type is enabled for a user.
     *
     * @param userId the user ID
     * @param type   the notification type
     * @return true if the notification type is enabled
     */
    boolean isNotificationEnabled(UUID userId, NotificationType type);

    /**
     * Check if the user is currently in quiet hours.
     *
     * @param userId the user ID
     * @return true if the user is in quiet hours
     */
    boolean isInQuietHours(UUID userId);

    /**
     * Get the raw preferences entity for internal use.
     *
     * @param userId the user ID
     * @return the preferences entity
     */
    UserNotificationPreferences getOrCreatePreferences(UUID userId);

    /**
     * Batch-fetch preferences for multiple users.
     * Users without saved preferences are assumed to have all notifications enabled.
     *
     * @param userIds list of user IDs
     * @return map of userId → preferences entity
     */
    Map<UUID, UserNotificationPreferences> getPreferencesByUserIds(List<UUID> userIds);
}
