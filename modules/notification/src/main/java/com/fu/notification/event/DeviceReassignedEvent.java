package com.fu.notification.event;

import lombok.Getter;

import java.util.UUID;

/**
 * Published when an FCM device token is reassigned from one user to another.
 * The evicted user should receive a security alert.
 */
@Getter
public class DeviceReassignedEvent {

    private final UUID evictedUserId;
    private final String deviceName;

    public DeviceReassignedEvent(UUID evictedUserId, String deviceName) {
        this.evictedUserId = evictedUserId;
        this.deviceName = deviceName;
    }
}
