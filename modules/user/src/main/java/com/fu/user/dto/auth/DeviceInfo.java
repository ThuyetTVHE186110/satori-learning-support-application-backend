package com.fu.user.dto.auth;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Device information for session tracking.
 * Used to identify and manage sessions across multiple devices.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeviceInfo {

    /**
     * Type of device: mobile, desktop, tablet
     */
    private String deviceType;

    /**
     * Operating system: iOS 17.2, Android 14, Windows 11, macOS
     */
    private String os;

    /**
     * Browser name: Chrome, Safari, Firefox
     */
    private String browser;

    /**
     * App version for mobile clients
     */
    private String appVersion;

    /**
     * Device model: iPhone 15, Samsung S24
     */
    private String deviceModel;

    /**
     * Push notification token (FCM/APNS)
     */
    private String pushToken;
}
