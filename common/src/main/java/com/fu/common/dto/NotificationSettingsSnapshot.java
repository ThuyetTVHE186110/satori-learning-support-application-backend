package com.fu.common.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalTime;

/**
 * Lightweight snapshot of a user's notification preferences for use in cross-module
 * aggregation (e.g. unified settings API in the user module).
 */
@Data
@Builder
public class NotificationSettingsSnapshot {

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
