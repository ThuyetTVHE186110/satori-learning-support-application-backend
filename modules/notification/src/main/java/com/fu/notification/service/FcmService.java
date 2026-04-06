package com.fu.notification.service;

import com.fu.notification.entity.Notification;
import com.google.firebase.messaging.BatchResponse;

import java.util.List;
import java.util.Map;

/**
 * Low-level service for sending push notifications via Firebase Cloud Messaging.
 */
public interface FcmService {

    /**
     * Check if FCM is available.
     *
     * @return true if FCM is configured and available
     */
    boolean isAvailable();

    /**
     * Send a push notification to a single device.
     *
     * @param fcmToken  the FCM token
     * @param title     notification title
     * @param body      notification body
     * @param data      additional data payload
     * @param imageUrl  optional image URL
     * @return true if sent successfully
     */
    boolean sendToDevice(String fcmToken, String title, String body, Map<String, String> data, String imageUrl);

    /**
     * Send push notifications to multiple devices.
     *
     * @param fcmTokens the FCM tokens
     * @param title     notification title
     * @param body      notification body
     * @param data      additional data payload
     * @param imageUrl  optional image URL
     * @return batch response with success/failure details
     */
    BatchResponse sendToDevices(List<String> fcmTokens, String title, String body, Map<String, String> data, String imageUrl);

    /**
     * Send a push notification to a topic.
     *
     * @param topic    the topic name
     * @param title    notification title
     * @param body     notification body
     * @param data     additional data payload
     * @param imageUrl optional image URL
     * @return true if sent successfully
     */
    boolean sendToTopic(String topic, String title, String body, Map<String, String> data, String imageUrl);
}
