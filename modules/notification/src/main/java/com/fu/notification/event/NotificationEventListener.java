package com.fu.notification.event;

import com.fu.notification.dto.SendNotificationRequest;
import com.fu.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Event listener for notification events.
 * Handles SendNotificationEvent published by other modules.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationEventListener {

    private final NotificationService notificationService;

    /**
     * Handles SendNotificationEvent.
     * Runs asynchronously to not block the publishing thread.
     */
    @Async
    @EventListener
    public void handleSendNotificationEvent(SendNotificationEvent event) {
        try {
            SendNotificationRequest request = SendNotificationRequest.builder()
                    .userId(event.getUserId())
                    .userIds(event.getUserIds())
                    .topic(event.getTopic())
                    .type(event.getType())
                    .priority(event.getPriority())
                    .title(event.getTitle())
                    .body(event.getBody())
                    .data(event.getData())
                    .imageUrl(event.getImageUrl())
                    .actionUrl(event.getActionUrl())
                    .saveToHistory(event.isSaveToHistory())
                    .bypassPreferences(event.isBypassPreferences())
                    .build();

            int sent = notificationService.send(request);
            log.debug("Notification event processed: type={}, sent={}", event.getType(), sent);
        } catch (Exception e) {
            log.error("Failed to process notification event: type={}, error={}",
                    event.getType(), e.getMessage(), e);
        }
    }
}
