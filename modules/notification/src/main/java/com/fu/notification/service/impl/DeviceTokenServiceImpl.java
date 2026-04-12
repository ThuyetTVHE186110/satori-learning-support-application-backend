package com.fu.notification.service.impl;

import com.fu.notification.dto.DeviceTokenResponse;
import com.fu.notification.dto.RegisterDeviceTokenRequest;
import com.fu.notification.entity.UserDeviceToken;
import com.fu.notification.event.DeviceReassignedEvent;
import com.fu.notification.repository.UserDeviceTokenRepository;
import com.fu.notification.service.DeviceTokenService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Implementation of DeviceTokenService.
 * Manages FCM device tokens for push notifications.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceTokenServiceImpl implements DeviceTokenService {

    private final UserDeviceTokenRepository deviceTokenRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    @Transactional
    public DeviceTokenResponse registerToken(UUID userId, RegisterDeviceTokenRequest request) {
        String fcmToken = request.getFcmToken();
        
        // Check if token already exists
        Optional<UserDeviceToken> existingToken = deviceTokenRepository.findByFcmToken(fcmToken);
        
        if (existingToken.isPresent()) {
            UserDeviceToken token = existingToken.get();
            
            // If same user, just update
            if (token.getUserId().equals(userId)) {
                token.setDeviceType(request.getDeviceType());
                token.setDeviceName(request.getDeviceName());
                token.setAppVersion(request.getAppVersion());
                token.markActive();
                
                UserDeviceToken saved = deviceTokenRepository.save(token);
                log.debug("Updated existing device token for user: {}", userId);
                return mapToResponse(saved);
            } else {
                // Token belongs to different user - reassign it and alert the evicted user
                UUID previousUserId = token.getUserId();
                token.setUserId(userId);
                token.setDeviceType(request.getDeviceType());
                token.setDeviceName(request.getDeviceName());
                token.setAppVersion(request.getAppVersion());
                token.markActive();

                UserDeviceToken saved = deviceTokenRepository.save(token);
                log.info("Reassigned device token from user {} to user {}", previousUserId, userId);

                // Notify the evicted user so they are aware their device is now signed into another account
                String deviceName = request.getDeviceName() != null ? request.getDeviceName() : "Thiết bị không xác định";
                eventPublisher.publishEvent(new DeviceReassignedEvent(previousUserId, deviceName));

                return mapToResponse(saved);
            }
        }

        // Create new token
        UserDeviceToken newToken = UserDeviceToken.builder()
                .userId(userId)
                .fcmToken(fcmToken)
                .deviceType(request.getDeviceType())
                .deviceName(request.getDeviceName())
                .appVersion(request.getAppVersion())
                .isActive(true)
                .lastActiveAt(LocalDateTime.now())
                .build();

        UserDeviceToken saved = deviceTokenRepository.save(newToken);
        log.debug("Registered new device token for user: {}", userId);
        return mapToResponse(saved);
    }

    @Override
    @Transactional
    public void unregisterToken(UUID userId, String fcmToken) {
        Optional<UserDeviceToken> token = deviceTokenRepository.findByFcmToken(fcmToken);
        
        if (token.isPresent() && token.get().getUserId().equals(userId)) {
            token.get().deactivate();
            deviceTokenRepository.save(token.get());
            log.debug("Unregistered device token for user: {}", userId);
        } else {
            log.warn("Attempted to unregister token not belonging to user: {}", userId);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<DeviceTokenResponse> getActiveTokens(UUID userId) {
        return deviceTokenRepository.findByUserIdAndIsActiveTrue(userId)
                .stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public int deactivateAllTokens(UUID userId) {
        int count = deviceTokenRepository.deactivateAllForUser(userId);
        log.info("Deactivated {} device tokens for user: {}", count, userId);
        return count;
    }

    @Override
    @Transactional
    public void handleInvalidTokens(List<String> invalidTokens) {
        if (invalidTokens == null || invalidTokens.isEmpty()) {
            return;
        }

        for (String token : invalidTokens) {
            deviceTokenRepository.deactivateByFcmToken(token);
        }
        log.info("Deactivated {} invalid FCM tokens", invalidTokens.size());
    }

    private DeviceTokenResponse mapToResponse(UserDeviceToken token) {
        return DeviceTokenResponse.builder()
                .id(token.getId())
                .deviceType(token.getDeviceType())
                .deviceName(token.getDeviceName())
                .appVersion(token.getAppVersion())
                .isActive(token.getIsActive())
                .lastActiveAt(token.getLastActiveAt())
                .createdAt(token.getCreatedAt())
                .build();
    }
}
