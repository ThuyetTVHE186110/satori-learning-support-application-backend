package com.fu.user.dto;

import com.fu.common.dto.NotificationSettingsSnapshot;
import lombok.Builder;
import lombok.Data;

/**
 * Aggregated settings response used by the unified settings API.
 * Combines profile, learning preferences, and notification preferences
 * so the frontend can populate a settings screen in a single request.
 */
@Data
@Builder
public class UserSettingsResponse {

    private UserProfileResponse profile;
    private NotificationSettingsSnapshot notificationPreferences;
}
