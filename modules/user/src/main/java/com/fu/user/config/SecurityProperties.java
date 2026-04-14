package com.fu.user.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Security configuration properties.
 * Properties under {@code app.security.*} in application.yml.
 *
 * Consolidates values previously scattered across @Value injections
 * in AuthServiceImpl, LoginAttemptServiceImpl, and AuthRateLimiter.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.security")
public class SecurityProperties {

    /** Max failed login attempts before account lock. */
    private int maxFailedAttempts = 5;

    /** Account lock duration in minutes after max failed attempts. */
    private int lockDurationMinutes = 5;

    /** OTP code expiration in minutes. */
    private int otpExpirationMinutes = 10;

    /** Password reset token expiration in minutes. */
    private int resetTokenExpirationMinutes = 5;

    /** Account activation token expiration in hours. */
    private int activationTokenExpirationHours = 24;

    /** Per-endpoint rate limiting configuration. */
    private RateLimits rateLimits = new RateLimits();

    @Getter
    @Setter
    public static class RateLimits {
        private RateLimit login = new RateLimit(10, 1);
        private RateLimit forgotPassword = new RateLimit(3, 10);
        private RateLimit register = new RateLimit(5, 1);
        private RateLimit verifyOtp = new RateLimit(5, 5);
    }

    @Getter
    @Setter
    public static class RateLimit {
        private int maxRequests;
        private int windowMinutes;

        public RateLimit() {}

        public RateLimit(int maxRequests, int windowMinutes) {
            this.maxRequests = maxRequests;
            this.windowMinutes = windowMinutes;
        }
    }
}
