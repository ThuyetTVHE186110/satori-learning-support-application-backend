package com.fu.user.dto.auth;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for logout.
 * UC-10: Logout
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LogoutRequest {

    /**
     * Refresh token to revoke.
     */
    private String refreshToken;

    /**
     * Whether to logout from all devices.
     * If true, all sessions for the user will be revoked.
     */
    @Builder.Default
    private boolean logoutAll = false;
}
