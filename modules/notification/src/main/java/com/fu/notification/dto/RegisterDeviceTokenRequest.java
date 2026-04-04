package com.fu.notification.dto;

import com.fu.notification.entity.DeviceType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for registering a device token.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RegisterDeviceTokenRequest {

    @NotBlank(message = "FCM token is required")
    @Size(max = 500, message = "FCM token must not exceed 500 characters")
    private String fcmToken;

    @NotNull(message = "Device type is required")
    private DeviceType deviceType;

    @Size(max = 100, message = "Device name must not exceed 100 characters")
    private String deviceName;

    @Size(max = 20, message = "App version must not exceed 20 characters")
    private String appVersion;
}
