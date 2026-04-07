package com.fu.notification.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalTime;
import java.util.UUID;

/**
 * Response DTO for notification preferences.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationPreferencesResponse {

    private UUID id;
    private UUID userId;
    
    // Global settings
    private Boolean pushEnabled;
    private Boolean emailEnabled;
    
    // Category settings
    private Boolean dailyReminderEnabled;
    private LocalTime dailyReminderTime;
    private Boolean assignmentNotifications;
    private Boolean achievementNotifications;
    private Boolean socialNotifications;
    private Boolean marketingNotifications;
    
    // Quiet hours
    private Boolean quietHoursEnabled;
    private LocalTime quietHoursStart;
    private LocalTime quietHoursEnd;
}
