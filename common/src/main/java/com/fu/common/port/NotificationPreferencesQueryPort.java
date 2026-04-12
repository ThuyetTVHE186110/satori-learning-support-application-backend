package com.fu.common.port;

import com.fu.common.dto.NotificationSettingsSnapshot;

import java.util.UUID;

/**
 * Port interface allowing the user module (and others) to read notification
 * preferences without a direct dependency on the notification module.
 *
 * Implemented in the notification module ({@code NotificationPreferencesQueryPortImpl}).
 */
public interface NotificationPreferencesQueryPort {

    /**
     * Returns a lightweight snapshot of a user's notification preferences,
     * or {@code null} if no preferences have been configured yet.
     */
    NotificationSettingsSnapshot getSnapshot(UUID userId);
}
