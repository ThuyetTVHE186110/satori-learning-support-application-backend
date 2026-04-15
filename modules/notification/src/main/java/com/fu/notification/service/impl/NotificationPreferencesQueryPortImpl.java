package com.fu.notification.service.impl;

import com.fu.common.dto.NotificationSettingsSnapshot;
import com.fu.common.port.NotificationPreferencesQueryPort;
import com.fu.notification.entity.UserNotificationPreferences;
import com.fu.notification.repository.UserNotificationPreferencesRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Implements {@link NotificationPreferencesQueryPort} for cross-module access
 * to notification preferences without exposing internal notification DTOs.
 */
@Service
@RequiredArgsConstructor
public class NotificationPreferencesQueryPortImpl implements NotificationPreferencesQueryPort {

    private final UserNotificationPreferencesRepository preferencesRepository;

    @Override
    @Transactional(readOnly = true)
    public NotificationSettingsSnapshot getSnapshot(UUID userId) {
        Optional<UserNotificationPreferences> opt = preferencesRepository.findByUserId(userId);
        if (opt.isEmpty()) {
            return null;
        }
        UserNotificationPreferences p = opt.get();
        return NotificationSettingsSnapshot.builder()
                .pushEnabled(p.getPushEnabled())
                .emailEnabled(p.getEmailEnabled())
                .dailyReminderEnabled(p.getDailyReminderEnabled())
                .dailyReminderTime(p.getDailyReminderTime())
                .assignmentNotifications(p.getAssignmentNotifications())
                .achievementNotifications(p.getAchievementNotifications())
                .classNotifications(p.getClassNotifications())
                .quietHoursEnabled(p.getQuietHoursEnabled())
                .quietHoursStart(p.getQuietHoursStart())
                .quietHoursEnd(p.getQuietHoursEnd())
                .build();
    }
}
