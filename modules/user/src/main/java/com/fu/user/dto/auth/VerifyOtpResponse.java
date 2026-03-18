package com.fu.user.dto.auth;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response DTO for OTP verification.
 * UC-07: Forgot Password - Step 2: Returns reset token after OTP verified
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VerifyOtpResponse {

    /**
     * Temporary reset token for resetting password.
     * Valid for a short period (e.g., 5 minutes).
     */
    private String resetToken;

    /**
     * Token expiration time in seconds.
     */
    private int expiresInSeconds;

    /**
     * User's email (for frontend confirmation display).
     */
    private String email;
}
