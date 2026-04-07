package com.fu.notification.service.impl;

import com.fu.notification.dto.NotificationPreferencesResponse;
import com.fu.notification.dto.UpdateNotificationPreferencesRequest;
import com.fu.notification.entity.NotificationType;
import com.fu.notification.entity.UserNotificationPreferences;
import com.fu.notification.repository.UserNotificationPreferencesRepository;
import com.fu.notification.service.NotificationPreferencesService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Implementation of NotificationPreferencesService.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationPreferencesServiceImpl implements NotificationPreferencesService {

    private final UserNotificationPreferencesRepository preferencesRepository;

    @Override
    @Transactional(readOnly = true)
    public NotificationPreferencesResponse getPreferences(UUID userId) {
        UserNotificationPreferences prefs = getOrCreatePreferences(userId);
        return mapToResponse(prefs);
    }

    @Override
    @Transactional
    public NotificationPreferencesResponse updatePreferences(UUID userId, UpdateNotificationPreferencesRequest request) {
        UserNotificationPreferences prefs = getOrCreatePreferences(userId);

        // Update only non-null fields
        if (request.getPushEnabled() != null) {
            prefs.setPushEnabled(request.getPushEnabled());
        }
        if (request.getEmailEnabled() != null) {
            prefs.setEmailEnabled(request.getEmailEnabled());
        }
        if (request.getDailyReminderEnabled() != null) {
            prefs.setDailyReminderEnabled(request.getDailyReminderEnabled());
        }
        if (request.getDailyReminderTime() != null) {
            prefs.setDailyReminderTime(request.getDailyReminderTime());
        }
        if (request.getAssignmentNotifications() != null) {
            prefs.setAssignmentNotifications(request.getAssignmentNotifications());
        }
        if (request.getAchievementNotifications() != null) {
            prefs.setAchievementNotifications(request.getAchievementNotifications());
        }
        if (request.getSocialNotifications() != null) {
            prefs.setSocialNotifications(request.getSocialNotifications());
        }
        if (request.getMarketingNotifications() != null) {
            prefs.setMarketingNotifications(request.getMarketingNotifications());
        }
        if (request.getQuietHoursEnabled() != null) {
            prefs.setQuietHoursEnabled(request.getQuietHoursEnabled());
        }
        if (request.getQuietHoursStart() != null) {
            prefs.setQuietHoursStart(request.getQuietHoursStart());
        }
        if (request.getQuietHoursEnd() != null) {
            prefs.setQuietHoursEnd(request.getQuietHoursEnd());
        }

        UserNotificationPreferences saved = preferencesRepository.save(prefs);
        log.debug("Updated notification preferences for user: {}", userId);
        return mapToResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isNotificationEnabled(UUID userId, NotificationType type) {
        return preferencesRepository.findByUserId(userId)
                .map(prefs -> prefs.isNotificationTypeEnabled(type))
                .orElse(true); // Default to enabled if no preferences set
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isInQuietHours(UUID userId) {
        return preferencesRepository.findByUserId(userId)
                .map(prefs -> prefs.isQuietTime(LocalTime.now()))
                .orElse(false);
    }

    @Override
    @Transactional
    public UserNotificationPreferences getOrCreatePreferences(UUID userId) {
        return preferencesRepository.findByUserId(userId)
                .orElseGet(() -> {
                    UserNotificationPreferences newPrefs = UserNotificationPreferences.createDefault(userId);
                    return preferencesRepository.save(newPrefs);
                });
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, UserNotificationPreferences> getPreferencesByUserIds(List<UUID> userIds) {
        return preferencesRepository.findByUserIds(userIds).stream()
                .collect(Collectors.toMap(UserNotificationPreferences::getUserId, p -> p));
    }

    private NotificationPreferencesResponse mapToResponse(UserNotificationPreferences prefs) {
        return NotificationPreferencesResponse.builder()
                .id(prefs.getId())
                .userId(prefs.getUserId())
                .pushEnabled(prefs.getPushEnabled())
                .emailEnabled(prefs.getEmailEnabled())
                .dailyReminderEnabled(prefs.getDailyReminderEnabled())
                .dailyReminderTime(prefs.getDailyReminderTime())
                .assignmentNotifications(prefs.getAssignmentNotifications())
                .achievementNotifications(prefs.getAchievementNotifications())
                .socialNotifications(prefs.getSocialNotifications())
                .marketingNotifications(prefs.getMarketingNotifications())
                .quietHoursEnabled(prefs.getQuietHoursEnabled())
                .quietHoursStart(prefs.getQuietHoursStart())
                .quietHoursEnd(prefs.getQuietHoursEnd())
                .build();
    }
}
