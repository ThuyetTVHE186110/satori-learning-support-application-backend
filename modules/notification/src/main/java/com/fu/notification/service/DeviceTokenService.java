package com.fu.notification.service;

import com.fu.notification.dto.DeviceTokenResponse;
import com.fu.notification.dto.RegisterDeviceTokenRequest;

import java.util.List;
import java.util.UUID;

/**
 * Service for managing user device tokens (FCM tokens).
 */
public interface DeviceTokenService {

    /**
     * Register a new device token for a user.
     * If the token already exists for another user, it will be reassigned.
     *
     * @param userId  the user ID
     * @param request the device token registration request
     * @return the registered device token
     */
    DeviceTokenResponse registerToken(UUID userId, RegisterDeviceTokenRequest request);

    /**
     * Unregister a device token.
     *
     * @param userId   the user ID
     * @param fcmToken the FCM token to unregister
     */
    void unregisterToken(UUID userId, String fcmToken);

    /**
     * Get all active device tokens for a user.
     *
     * @param userId the user ID
     * @return list of active device tokens
     */
    List<DeviceTokenResponse> getActiveTokens(UUID userId);

    /**
     * Deactivate all tokens for a user (e.g., on password change or logout from all devices).
     *
     * @param userId the user ID
     * @return number of tokens deactivated
     */
    int deactivateAllTokens(UUID userId);

    /**
     * Update the last active timestamp for a token.
     *
     * @param fcmToken the FCM token
     */
    void markTokenActive(String fcmToken);

    /**
     * Handle invalid tokens returned by FCM.
     * Marks them as inactive.
     *
     * @param invalidTokens list of invalid FCM tokens
     */
    void handleInvalidTokens(List<String> invalidTokens);
}
