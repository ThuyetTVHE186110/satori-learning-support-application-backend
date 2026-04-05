package com.fu.common.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.util.UUID;

/**
 * Event fired when a user changes their password.
 * Triggers security notification.
 */
@Getter
public class PasswordChangedEvent extends ApplicationEvent {

    private final UUID userId;
    private final String userEmail;
    private final String deviceInfo;
    private final String ipAddress;

    public PasswordChangedEvent(Object source, UUID userId, String userEmail,
                                 String deviceInfo, String ipAddress) {
        super(source);
        this.userId = userId;
        this.userEmail = userEmail;
        this.deviceInfo = deviceInfo;
        this.ipAddress = ipAddress;
    }
}
