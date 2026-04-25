package com.fu.notification.service.impl;

import com.fu.common.port.UserQueryPort;
import com.fu.infrastructure.email.EmailService;
import com.fu.notification.dto.NotificationResponse;
import com.fu.notification.dto.NotificationPreferencesResponse;
import com.fu.notification.dto.SendNotificationRequest;
import com.fu.notification.entity.Notification;
import com.fu.notification.entity.NotificationPriority;
import com.fu.notification.entity.UserDeviceToken;
import com.fu.notification.entity.UserNotificationPreferences;
import com.fu.notification.repository.NotificationRepository;
import com.fu.notification.repository.UserDeviceTokenRepository;
import com.fu.notification.service.DeviceTokenService;
import com.fu.notification.service.FcmService;
import com.fu.notification.service.NotificationPreferencesService;
import com.fu.notification.service.NotificationService;
import com.fu.notification.sse.NotificationSseEmitterRegistry;
import com.google.firebase.messaging.BatchResponse;
import com.google.firebase.messaging.SendResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Implementation of NotificationService.
 * Handles sending notifications via FCM and persisting to database.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationServiceImpl implements NotificationService {

    private final NotificationRepository notificationRepository;
    private final UserDeviceTokenRepository deviceTokenRepository;
    private final DeviceTokenService deviceTokenService;
    private final NotificationPreferencesService preferencesService;
    private final FcmService fcmService;
    private final EmailService emailService;
    private final UserQueryPort userQueryPort;
    private final NotificationSseEmitterRegistry sseRegistry;

    @Override
    @Transactional
    public int send(SendNotificationRequest request) {
        if (request.getUserId() != null) {
            return sendToUser(request.getUserId(), request) ? 1 : 0;
        } else if (request.getUserIds() != null && !request.getUserIds().isEmpty()) {
            return sendToUsers(request.getUserIds(), request);
        } else if (request.getTopic() != null) {
            return sendToTopic(request.getTopic(), request) ? 1 : 0;
        }
        
        log.warn("No target specified for notification");
        return 0;
    }

    private static final int DEDUP_WINDOW_MINUTES = 5;

    @Override
    @Transactional
    public boolean sendToUser(UUID userId, SendNotificationRequest request) {
        log.info("[NOTIF-TRACE] sendToUser ENTER userId={}, type={}", userId, request.getType());
        // Check user preferences (unless bypassed)
        if (!request.isBypassPreferences()) {
            if (!preferencesService.isNotificationEnabled(userId, request.getType())) {
                log.debug("Notification type {} disabled for user {}", request.getType(), userId);
                return false;
            }

            // Skip non-critical notifications during quiet hours
            if (request.getPriority() != NotificationPriority.CRITICAL &&
                preferencesService.isInQuietHours(userId)) {
                log.debug("User {} is in quiet hours, skipping notification", userId);
                return false;
            }

            // Deduplication: skip if identical type was already sent recently
            LocalDateTime deduplicationSince = LocalDateTime.now().minusMinutes(DEDUP_WINDOW_MINUTES);
            List<Notification> recent = notificationRepository.findRecentByUserAndType(
                    userId, request.getType().name(), deduplicationSince);
            if (!recent.isEmpty()) {
                log.debug("Dedup: skipping {} for user {} (sent {} min ago)",
                        request.getType(), userId, DEDUP_WINDOW_MINUTES);
                return false;
            }
        }

        // Save to history if requested
        Notification notification = null;
        if (request.isSaveToHistory()) {
            notification = saveNotification(userId, request);
        }

        // Get active device tokens
        List<UserDeviceToken> tokens = deviceTokenRepository.findByUserIdAndIsActiveTrue(userId);
        log.info("[NOTIF-TRACE] tokens for user {}: count={}", userId, tokens.size());
        if (tokens.isEmpty()) {
            log.debug("No active device tokens for user {}", userId);
            return notification != null;
        }

        // Send push notification
        boolean pushSent = sendPushToTokens(
                tokens.stream().map(UserDeviceToken::getFcmToken).collect(Collectors.toList()),
                request
        );
        log.info("[NOTIF-TRACE] pushSent={} for user {}", pushSent, userId);

        // Update notification push status
        if (notification != null && pushSent) {
            notification.markPushSent();
            notificationRepository.save(notification);
        }

        // Email notification for HIGH/CRITICAL priority when user opted in
        if (request.getPriority() == NotificationPriority.HIGH
                || request.getPriority() == NotificationPriority.CRITICAL) {
            try {
                NotificationPreferencesResponse prefs = preferencesService.getPreferences(userId);
                if (prefs != null && Boolean.TRUE.equals(prefs.getEmailEnabled())) {
                    String email = userQueryPort.getUserEmail(userId);
                    if (email != null) {
                        emailService.sendSimpleEmail(email, request.getTitle(), request.getBody());
                    }
                }
            } catch (Exception ex) {
                log.warn("Email notification failed for user {}: {}", userId, ex.getMessage());
            }
        }

        return true;
    }

    @Override
    @Transactional
    public int sendToUsers(List<UUID> userIds, SendNotificationRequest request) {
        if (userIds == null || userIds.isEmpty()) {
            return 0;
        }
        int successCount = 0;

        // Batch-fetch preferences in one query (O(1) instead of O(N))
        Map<UUID, UserNotificationPreferences> prefsMap = request.isBypassPreferences()
                ? Collections.emptyMap()
                : preferencesService.getPreferencesByUserIds(userIds);

        // Filter users based on preferences
        List<UUID> eligibleUsers = userIds.stream()
                .filter(userId -> {
                    if (request.isBypassPreferences()) return true;
                    UserNotificationPreferences prefs = prefsMap.get(userId);
                    if (prefs == null) return true; // no prefs → default enabled
                    return prefs.isNotificationTypeEnabled(request.getType()) &&
                           (request.getPriority() == NotificationPriority.CRITICAL ||
                            !prefs.isQuietTime(LocalTime.now()));
                })
                .collect(Collectors.toList());

        if (eligibleUsers.isEmpty()) {
            log.debug("No eligible users for notification after filtering");
            return 0;
        }

        // Save notifications to history
        if (request.isSaveToHistory()) {
            List<Notification> notifications = eligibleUsers.stream()
                    .map(userId -> buildNotification(userId, request))
                    .collect(Collectors.toList());
            notificationRepository.saveAll(notifications);
            successCount = notifications.size();
        }

        // Get all active tokens for eligible users
        List<UserDeviceToken> allTokens = deviceTokenRepository.findActiveTokensByUserIds(eligibleUsers);
        if (!allTokens.isEmpty()) {
            List<String> fcmTokens = allTokens.stream()
                    .map(UserDeviceToken::getFcmToken)
                    .collect(Collectors.toList());
            
            sendPushToTokens(fcmTokens, request);
        }

        return successCount;
    }

    @Override
    @Transactional
    public boolean sendToTopic(String topic, SendNotificationRequest request) {
        // Save to history for topic subscribers would require knowing who's subscribed
        // For now, just send the push
        
        Map<String, String> data = convertDataToStringMap(request.getData());
        data.put("type", request.getType().name());
        if (request.getActionUrl() != null) {
            data.put("actionUrl", request.getActionUrl());
        }

        return fcmService.sendToTopic(topic, request.getTitle(), request.getBody(), data, request.getImageUrl());
    }

    @Override
    @Transactional(readOnly = true)
    public Page<NotificationResponse> getNotifications(UUID userId, Pageable pageable) {
        return notificationRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable)
                .map(this::mapToResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<NotificationResponse> getUnreadNotifications(UUID userId, Pageable pageable) {
        return notificationRepository.findByUserIdAndIsReadFalseOrderByCreatedAtDesc(userId, pageable)
                .map(this::mapToResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public long getUnreadCount(UUID userId) {
        return notificationRepository.countByUserIdAndIsReadFalse(userId);
    }

    @Override
    @Transactional
    public int markAsRead(UUID userId, List<UUID> notificationIds) {
        return notificationRepository.markAsRead(notificationIds, userId, LocalDateTime.now());
    }

    @Override
    @Transactional
    public int markAllAsRead(UUID userId) {
        return notificationRepository.markAllAsReadForUser(userId, LocalDateTime.now());
    }

    @Override
    @Transactional
    public void deleteNotification(UUID userId, UUID notificationId) {
        notificationRepository.findById(notificationId)
                .filter(n -> n.getUserId().equals(userId))
                .ifPresent(notificationRepository::delete);
    }

    // ── Private Helpers ───────────────────────────────────────────────────────

    private Notification saveNotification(UUID userId, SendNotificationRequest request) {
        Notification notification = buildNotification(userId, request);
        Notification saved = notificationRepository.save(notification);
        // Push to any open SSE streams for real-time in-app delivery (null-safe for test environments)
        if (sseRegistry != null) {
            sseRegistry.send(userId, mapToResponse(saved));
        }
        return saved;
    }

    private Notification buildNotification(UUID userId, SendNotificationRequest request) {
        return Notification.builder()
                .userId(userId)
                .type(request.getType())
                .priority(request.getPriority())
                .title(request.getTitle())
                .body(request.getBody())
                .data(request.getData())
                .imageUrl(request.getImageUrl())
                .actionUrl(request.getActionUrl())
                .isRead(false)
                .pushSent(false)
                .build();
    }

    private boolean sendPushToTokens(List<String> fcmTokens, SendNotificationRequest request) {
        if (!fcmService.isAvailable() || fcmTokens.isEmpty()) {
            return false;
        }

        Map<String, String> data = convertDataToStringMap(request.getData());
        data.put("type", request.getType().name());
        if (request.getActionUrl() != null) {
            data.put("actionUrl", request.getActionUrl());
        }

        if (fcmTokens.size() == 1) {
            return fcmService.sendToDevice(fcmTokens.get(0), request.getTitle(), request.getBody(), 
                    data, request.getImageUrl());
        }

        BatchResponse response = fcmService.sendToDevices(fcmTokens, request.getTitle(), request.getBody(),
                data, request.getImageUrl());
        
        if (response != null) {
            // Handle invalid tokens
            List<String> invalidTokens = new ArrayList<>();
            List<SendResponse> responses = response.getResponses();
            for (int i = 0; i < responses.size(); i++) {
                if (!responses.get(i).isSuccessful()) {
                    invalidTokens.add(fcmTokens.get(i));
                }
            }
            
            if (!invalidTokens.isEmpty()) {
                // Deactivate invalid tokens asynchronously
                handleInvalidTokens(invalidTokens);
            }
            
            return response.getSuccessCount() > 0;
        }
        
        return false;
    }

    private void handleInvalidTokens(List<String> invalidTokens) {
        deviceTokenService.handleInvalidTokens(invalidTokens);
    }

    private Map<String, String> convertDataToStringMap(Map<String, Object> data) {
        Map<String, String> stringMap = new HashMap<>();
        if (data != null) {
            for (Map.Entry<String, Object> entry : data.entrySet()) {
                if (entry.getValue() != null) {
                    stringMap.put(entry.getKey(), entry.getValue().toString());
                }
            }
        }
        return stringMap;
    }

    private NotificationResponse mapToResponse(Notification notification) {
        return NotificationResponse.builder()
                .id(notification.getId())
                .type(notification.getType())
                .priority(notification.getPriority())
                .title(notification.getTitle())
                .body(notification.getBody())
                .data(notification.getData())
                .imageUrl(notification.getImageUrl())
                .actionUrl(notification.getActionUrl())
                .isRead(notification.getIsRead())
                .readAt(notification.getReadAt())
                .createdAt(notification.getCreatedAt())
                .build();
    }
}
