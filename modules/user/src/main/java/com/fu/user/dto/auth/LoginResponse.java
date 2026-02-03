package com.fu.user.dto.auth;

import com.fu.user.entity.UserRole;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Response DTO for successful authentication.
 * Contains JWT tokens and user information.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoginResponse {

    /**
     * JWT Access Token for API authorization.
     * Short-lived (default: 30 minutes).
     */
    private String accessToken;

    /**
     * Refresh token for obtaining new access tokens.
     * Longer-lived (default: 7 days).
     */
    private String refreshToken;

    /**
     * Token type (always "Bearer").
     */
    @Builder.Default
    private String tokenType = "Bearer";

    /**
     * Access token expiration time in seconds.
     */
    private long expiresIn;

    /**
     * Basic user information.
     */
    private UserInfo user;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UserInfo {
        private UUID id;
        private String email;
        private String fullName;
        private String avatarUrl;
        private UserRole role;
    }
}
