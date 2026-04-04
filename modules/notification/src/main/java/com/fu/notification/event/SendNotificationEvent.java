package com.fu.notification.event;

import com.fu.notification.entity.NotificationPriority;
import com.fu.notification.entity.NotificationType;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Application event for sending notifications.
 * Published by other modules to trigger notification delivery.
 */
@Getter
public class SendNotificationEvent extends ApplicationEvent {

    private final UUID userId;
    private final List<UUID> userIds;
    private final String topic;
    private final NotificationType type;
    private final NotificationPriority priority;
    private final String title;
    private final String body;
    private final Map<String, Object> data;
    private final String imageUrl;
    private final String actionUrl;
    private final boolean saveToHistory;
    private final boolean bypassPreferences;

    private SendNotificationEvent(Builder builder) {
        super(builder.source);
        this.userId = builder.userId;
        this.userIds = builder.userIds;
        this.topic = builder.topic;
        this.type = builder.type;
        this.priority = builder.priority;
        this.title = builder.title;
        this.body = builder.body;
        this.data = builder.data;
        this.imageUrl = builder.imageUrl;
        this.actionUrl = builder.actionUrl;
        this.saveToHistory = builder.saveToHistory;
        this.bypassPreferences = builder.bypassPreferences;
    }

    public static Builder builder(Object source) {
        return new Builder(source);
    }

    public static class Builder {
        private final Object source;
        private UUID userId;
        private List<UUID> userIds;
        private String topic;
        private NotificationType type;
        private NotificationPriority priority = NotificationPriority.NORMAL;
        private String title;
        private String body;
        private Map<String, Object> data;
        private String imageUrl;
        private String actionUrl;
        private boolean saveToHistory = true;
        private boolean bypassPreferences = false;

        private Builder(Object source) {
            this.source = source;
        }

        public Builder userId(UUID userId) {
            this.userId = userId;
            return this;
        }

        public Builder userIds(List<UUID> userIds) {
            this.userIds = userIds;
            return this;
        }

        public Builder topic(String topic) {
            this.topic = topic;
            return this;
        }

        public Builder type(NotificationType type) {
            this.type = type;
            return this;
        }

        public Builder priority(NotificationPriority priority) {
            this.priority = priority;
            return this;
        }

        public Builder title(String title) {
            this.title = title;
            return this;
        }

        public Builder body(String body) {
            this.body = body;
            return this;
        }

        public Builder data(Map<String, Object> data) {
            this.data = data;
            return this;
        }

        public Builder imageUrl(String imageUrl) {
            this.imageUrl = imageUrl;
            return this;
        }

        public Builder actionUrl(String actionUrl) {
            this.actionUrl = actionUrl;
            return this;
        }

        public Builder saveToHistory(boolean saveToHistory) {
            this.saveToHistory = saveToHistory;
            return this;
        }

        public Builder bypassPreferences(boolean bypassPreferences) {
            this.bypassPreferences = bypassPreferences;
            return this;
        }

        public SendNotificationEvent build() {
            return new SendNotificationEvent(this);
        }
    }
}
