package com.fu.user.security;

import com.fu.common.exception.BusinessException;
import com.fu.user.config.SecurityProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Per-IP rate limiter for public authentication endpoints.
 *
 * Uses the same fixed-window Redis counter pattern as ChatRateLimiter.
 * Key format: "auth:rate:{endpoint}:{ip}"
 *
 * Fail-open: if Redis is unavailable, the request is allowed through.
 * IP extraction is handled by the caller (AuthController.getClientIpAddress).
 */
@Slf4j
@Component
public class AuthRateLimiter {

    private static final String KEY_PREFIX = "auth:rate:";

    private final RedisTemplate<String, Object> redisTemplate;
    private final SecurityProperties securityProperties;

    public AuthRateLimiter(RedisTemplate<String, Object> redisTemplate,
                           SecurityProperties securityProperties) {
        this.redisTemplate = redisTemplate;
        this.securityProperties = securityProperties;
    }

    // ── Public check methods ───────────────────────────────────────────────

    public void checkLogin(String ipAddress) {
        SecurityProperties.RateLimit rl = securityProperties.getRateLimits().getLogin();
        check("login", ipAddress, rl.getMaxRequests(), Duration.ofMinutes(rl.getWindowMinutes()),
                String.format("Quá nhiều lần đăng nhập. Vui lòng thử lại sau %d phút.", rl.getWindowMinutes()));
    }

    public void checkForgotPassword(String ipAddress) {
        SecurityProperties.RateLimit rl = securityProperties.getRateLimits().getForgotPassword();
        check("forgot-password", ipAddress, rl.getMaxRequests(), Duration.ofMinutes(rl.getWindowMinutes()),
                String.format("Quá nhiều yêu cầu đặt lại mật khẩu. Vui lòng thử lại sau %d phút.", rl.getWindowMinutes()));
    }

    public void checkVerifyOtp(String ipAddress) {
        SecurityProperties.RateLimit rl = securityProperties.getRateLimits().getVerifyOtp();
        check("verify-otp", ipAddress, rl.getMaxRequests(), Duration.ofMinutes(rl.getWindowMinutes()),
                String.format("Quá nhiều lần nhập OTP. Vui lòng thử lại sau %d phút.", rl.getWindowMinutes()));
    }

    // ── Core logic ────────────────────────────────────────────────────────

    private void check(String endpoint, String ipAddress, int maxRequests,
                       Duration window, String errorMessage) {
        String key = KEY_PREFIX + endpoint + ":" + sanitizeIp(ipAddress);
        try {
            Long count = redisTemplate.opsForValue().increment(key);
            if (count == null) return; // unexpected Redis null — fail open

            if (count == 1) {
                redisTemplate.expire(key, window);
            }

            if (count > maxRequests) {
                redisTemplate.opsForValue().decrement(key);
                log.warn("Auth rate limit exceeded: endpoint={} ip={} count={} limit={}",
                        endpoint, ipAddress, count, maxRequests);
                throw new BusinessException("RATE_LIMIT_EXCEEDED", errorMessage);
            }

        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("AuthRateLimiter: Redis error for endpoint={} ip={}: {}",
                    endpoint, ipAddress, e.getMessage());
        }
    }

    private static String sanitizeIp(String ip) {
        if (ip == null) return "unknown";
        String normalized = ip.replaceFirst("^::ffff:", "");
        int lastColon = normalized.lastIndexOf(':');
        if (lastColon > 0 && normalized.indexOf(':') == lastColon) {
            normalized = normalized.substring(0, lastColon);
        }
        return normalized;
    }
}
