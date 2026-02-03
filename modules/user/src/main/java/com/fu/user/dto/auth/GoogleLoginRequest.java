package com.fu.user.dto.auth;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for Google OAuth login.
 * UC-09: Login with Google
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GoogleLoginRequest {

    /**
     * Google ID Token received from Google Sign-In.
     * This token will be verified with Google's servers.
     */
    @NotBlank(message = "Google ID Token là bắt buộc")
    private String idToken;

    /**
     * Optional device information for session tracking.
     */
    private DeviceInfo deviceInfo;
}
