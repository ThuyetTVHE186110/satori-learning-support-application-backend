package com.fu.notification.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalTime;

/**
 * Request DTO for updating notification preferences.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateNotificationPreferencesRequest {

    private Boolean pushEnabled;
    private Boolean emailEnabled;
    private Boolean dailyReminderEnabled;
    private LocalTime dailyReminderTime;
    private Boolean assignmentNotifications;
    private Boolean achievementNotifications;
    private Boolean socialNotifications;
    private Boolean marketingNotifications;
    private Boolean quietHoursEnabled;
    private LocalTime quietHoursStart;
    private LocalTime quietHoursEnd;
}
