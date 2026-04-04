package com.fu.notification.scheduler;

import com.fu.notification.config.NotificationProperties;
import com.fu.notification.repository.NotificationRepository;
import com.fu.notification.repository.UserDeviceTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Scheduled job for cleaning up old notifications and inactive device tokens.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationCleanupScheduler {

    private final NotificationRepository notificationRepository;
    private final UserDeviceTokenRepository deviceTokenRepository;
    private final NotificationProperties properties;

    /**
     * Clean up old read notifications.
     * Runs daily at 3:00 AM.
     */
    @Scheduled(cron = "0 0 3 * * ?")
    @Transactional
    public void cleanupReadNotifications() {
        int retentionDays = properties.getCleanup().getReadNotificationRetentionDays();
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        
        int deleted = notificationRepository.deleteReadOlderThan(cutoff);
        if (deleted > 0) {
            log.info("Cleaned up {} read notifications older than {} days", deleted, retentionDays);
        }
    }

    /**
     * Clean up all old notifications (regardless of read status).
     * Runs daily at 3:30 AM.
     */
    @Scheduled(cron = "0 30 3 * * ?")
    @Transactional
    public void cleanupOldNotifications() {
        int retentionDays = properties.getCleanup().getAllNotificationRetentionDays();
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        
        int deleted = notificationRepository.deleteOlderThan(cutoff);
        if (deleted > 0) {
            log.info("Cleaned up {} old notifications older than {} days", deleted, retentionDays);
        }
    }

    /**
     * Clean up inactive device tokens.
     * Runs daily at 4:00 AM.
     */
    @Scheduled(cron = "0 0 4 * * ?")
    @Transactional
    public void cleanupInactiveTokens() {
        int retentionDays = properties.getCleanup().getInactiveTokenRetentionDays();
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        
        int deleted = deviceTokenRepository.deleteInactiveTokensOlderThan(cutoff);
        if (deleted > 0) {
            log.info("Cleaned up {} inactive device tokens older than {} days", deleted, retentionDays);
        }
    }
}
