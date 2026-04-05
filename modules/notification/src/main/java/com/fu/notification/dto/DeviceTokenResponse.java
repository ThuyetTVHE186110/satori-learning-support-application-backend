package com.fu.notification.dto;

import com.fu.notification.entity.DeviceType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Response DTO for device token information.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeviceTokenResponse {

    private UUID id;
    private DeviceType deviceType;
    private String deviceName;
    private String appVersion;
    private Boolean isActive;
    private LocalDateTime lastActiveAt;
    private LocalDateTime createdAt;
}
