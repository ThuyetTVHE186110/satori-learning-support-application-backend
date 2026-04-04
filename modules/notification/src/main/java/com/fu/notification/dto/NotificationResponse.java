package com.fu.notification.dto;

import com.fu.notification.entity.NotificationPriority;
import com.fu.notification.entity.NotificationType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Response DTO for notification details.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationResponse {

    private UUID id;
    private NotificationType type;
    private NotificationPriority priority;
    private String title;
    private String body;
    private Map<String, Object> data;
    private String imageUrl;
    private String actionUrl;
    private Boolean isRead;
    private LocalDateTime readAt;
    private LocalDateTime createdAt;
}
