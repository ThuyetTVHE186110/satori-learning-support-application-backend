package com.fu.notification.service.impl;

import com.fu.notification.config.NotificationProperties;
import com.fu.notification.service.FcmService;
import com.google.firebase.messaging.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Firebase Cloud Messaging service implementation.
 * Handles low-level FCM operations.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FcmServiceImpl implements FcmService {

    private final Optional<FirebaseMessaging> firebaseMessaging;
    private final NotificationProperties properties;

    @Override
    public boolean isAvailable() {
        return firebaseMessaging.isPresent();
    }

    @Override
    public boolean sendToDevice(String fcmToken, String title, String body, 
                                 Map<String, String> data, String imageUrl) {
        if (!isAvailable()) {
            log.debug("FCM not available, skipping push to device");
            return false;
        }

        try {
            Message message = buildMessage(fcmToken, null, title, body, data, imageUrl);
            String response = firebaseMessaging.get().send(message);
            log.debug("FCM message sent successfully: {}", response);
            return true;
        } catch (FirebaseMessagingException e) {
            handleFcmException(e, fcmToken);
            return false;
        }
    }

    @Override
    public BatchResponse sendToDevices(List<String> fcmTokens, String title, String body,
                                        Map<String, String> data, String imageUrl) {
        if (!isAvailable()) {
            log.debug("FCM not available, skipping batch push");
            return null;
        }

        if (fcmTokens == null || fcmTokens.isEmpty()) {
            log.debug("No tokens provided for batch send");
            return null;
        }

        try {
            // Build the base notification
            Notification notification = Notification.builder()
                    .setTitle(title)
                    .setBody(body)
                    .setImage(imageUrl)
                    .build();

            // Build messages for each token
            List<Message> messages = new ArrayList<>();
            for (String token : fcmTokens) {
                Message.Builder messageBuilder = Message.builder()
                        .setToken(token)
                        .setNotification(notification);

                if (data != null && !data.isEmpty()) {
                    messageBuilder.putAllData(data);
                }

                // Add platform-specific config
                messageBuilder.setAndroidConfig(buildAndroidConfig())
                        .setApnsConfig(buildApnsConfig())
                        .setWebpushConfig(buildWebpushConfig(title, body, imageUrl));

                messages.add(messageBuilder.build());
            }

            // Send in batches (FCM limit is 500)
            int batchSize = properties.getBatch().getMaxBatchSize();
            BatchResponse finalResponse = null;
            
            for (int i = 0; i < messages.size(); i += batchSize) {
                List<Message> batch = messages.subList(i, Math.min(i + batchSize, messages.size()));
                BatchResponse response = firebaseMessaging.get().sendEach(batch);
                
                log.debug("FCM batch sent: {} success, {} failure",
                        response.getSuccessCount(), response.getFailureCount());
                
                if (finalResponse == null) {
                    finalResponse = response;
                }

                // Add delay between batches
                if (i + batchSize < messages.size()) {
                    Thread.sleep(properties.getBatch().getBatchDelayMs());
                }
            }

            return finalResponse;
        } catch (FirebaseMessagingException e) {
            log.error("Failed to send FCM batch: {}", e.getMessage());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Batch send interrupted");
            return null;
        }
    }

    @Override
    public boolean sendToTopic(String topic, String title, String body,
                                Map<String, String> data, String imageUrl) {
        if (!isAvailable()) {
            log.debug("FCM not available, skipping topic push");
            return false;
        }

        try {
            Message message = buildMessage(null, topic, title, body, data, imageUrl);
            String response = firebaseMessaging.get().send(message);
            log.debug("FCM topic message sent successfully: {}", response);
            return true;
        } catch (FirebaseMessagingException e) {
            log.error("Failed to send FCM topic message: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public int subscribeToTopic(List<String> fcmTokens, String topic) {
        if (!isAvailable() || fcmTokens == null || fcmTokens.isEmpty()) {
            return 0;
        }

        try {
            TopicManagementResponse response = firebaseMessaging.get()
                    .subscribeToTopic(fcmTokens, topic);
            log.debug("Subscribed {} tokens to topic '{}', {} failures",
                    response.getSuccessCount(), topic, response.getFailureCount());
            return response.getSuccessCount();
        } catch (FirebaseMessagingException e) {
            log.error("Failed to subscribe to topic: {}", e.getMessage());
            return 0;
        }
    }

    @Override
    public int unsubscribeFromTopic(List<String> fcmTokens, String topic) {
        if (!isAvailable() || fcmTokens == null || fcmTokens.isEmpty()) {
            return 0;
        }

        try {
            TopicManagementResponse response = firebaseMessaging.get()
                    .unsubscribeFromTopic(fcmTokens, topic);
            log.debug("Unsubscribed {} tokens from topic '{}', {} failures",
                    response.getSuccessCount(), topic, response.getFailureCount());
            return response.getSuccessCount();
        } catch (FirebaseMessagingException e) {
            log.error("Failed to unsubscribe from topic: {}", e.getMessage());
            return 0;
        }
    }

    // ── Private Helpers ───────────────────────────────────────────────────────

    private Message buildMessage(String token, String topic, String title, String body,
                                  Map<String, String> data, String imageUrl) {
        Notification notification = Notification.builder()
                .setTitle(title)
                .setBody(body)
                .setImage(imageUrl)
                .build();

        Message.Builder builder = Message.builder()
                .setNotification(notification)
                .setAndroidConfig(buildAndroidConfig())
                .setApnsConfig(buildApnsConfig())
                .setWebpushConfig(buildWebpushConfig(title, body, imageUrl));

        if (token != null) {
            builder.setToken(token);
        }
        if (topic != null) {
            builder.setTopic(topic);
        }
        if (data != null && !data.isEmpty()) {
            builder.putAllData(data);
        }

        return builder.build();
    }

    private AndroidConfig buildAndroidConfig() {
        return AndroidConfig.builder()
                .setPriority(AndroidConfig.Priority.HIGH)
                .setNotification(AndroidNotification.builder()
                        .setSound("default")
                        .setClickAction("OPEN_ACTIVITY")
                        .build())
                .build();
    }

    private ApnsConfig buildApnsConfig() {
        return ApnsConfig.builder()
                .setAps(Aps.builder()
                        .setSound("default")
                        .setBadge(1)
                        .build())
                .build();
    }

    private WebpushConfig buildWebpushConfig(String title, String body, String imageUrl) {
        WebpushNotification.Builder notificationBuilder = WebpushNotification.builder()
                .setTitle(title)
                .setBody(body)
                .setIcon("/icon-192x192.png")
                .setRequireInteraction(true);
        
        if (imageUrl != null) {
            notificationBuilder.setImage(imageUrl);
        }

        return WebpushConfig.builder()
                .setNotification(notificationBuilder.build())
                .build();
    }

    private void handleFcmException(FirebaseMessagingException e, String token) {
        MessagingErrorCode errorCode = e.getMessagingErrorCode();
        if (errorCode == MessagingErrorCode.UNREGISTERED ||
            errorCode == MessagingErrorCode.INVALID_ARGUMENT) {
            log.warn("Invalid FCM token detected: {}...", token.substring(0, Math.min(20, token.length())));
            // Token should be deactivated by the caller
        } else {
            log.error("FCM error [{}]: {}", errorCode, e.getMessage());
        }
    }
}
