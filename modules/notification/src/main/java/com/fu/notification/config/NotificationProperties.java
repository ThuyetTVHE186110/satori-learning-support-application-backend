package com.fu.notification.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration properties for the notification module.
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "app.notification")
public class NotificationProperties {

    private Firebase firebase = new Firebase();
    private Cleanup cleanup = new Cleanup();
    private Batch batch = new Batch();

    @Data
    public static class Firebase {
        /**
         * Enable/disable Firebase Cloud Messaging.
         */
        private boolean enabled = false;

        /**
         * Path to Firebase service account credentials JSON file.
         */
        private String credentialsFile;

        /**
         * Firebase project ID.
         */
        private String projectId;
    }

    @Data
    public static class Cleanup {
        /**
         * Days to keep read notifications before deletion.
         */
        private int readNotificationRetentionDays = 30;

        /**
         * Days to keep all notifications before deletion.
         */
        private int allNotificationRetentionDays = 90;

        /**
         * Days to keep inactive device tokens before deletion.
         */
        private int inactiveTokenRetentionDays = 30;
    }

    @Data
    public static class Batch {
        /**
         * Maximum number of tokens to send in a single FCM batch.
         * FCM limit is 500.
         */
        private int maxBatchSize = 500;

        /**
         * Delay between batches in milliseconds.
         */
        private long batchDelayMs = 100;
    }
}
