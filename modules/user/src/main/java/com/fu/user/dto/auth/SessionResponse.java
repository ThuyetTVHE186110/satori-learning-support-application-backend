package com.fu.user.dto.auth;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Response DTO for session information.
 * Used to display active sessions to users.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionResponse {

    private UUID id;
    
    /**
     * Device information (structured).
     */
    private DeviceInfo deviceInfo;
    
    /**
     * Human-readable device description.
     * Examples: "iPhone 15 (iOS 17.2) - App v2.1.0", "Chrome on Windows 10/11"
     */
    private String deviceDescription;
    
    /**
     * Platform type: "app" or "web"
     */
    private String platform;
    
    private String ipAddress;
    private LocalDateTime createdAt;
    private LocalDateTime expiresAt;
    private LocalDateTime lastActiveAt;
    
    /**
     * Whether this is the current session making the request.
     */
    private boolean currentSession;
}
