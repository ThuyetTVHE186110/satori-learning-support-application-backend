package com.fu.user.security;

import com.fu.common.exception.BusinessException;
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
 * Limits:
 *   login           — 10 attempts / 1 min  (covers both success and failure)
 *   forgot-password — 3  attempts / 10 min (prevents OTP spam / email flooding)
 *   register        — 5  attempts / 1 min
 *   verify-otp      — 5  attempts / 5 min
 *
 * Fail-open: if Redis is unavailable, the request is allowed through.
 * IP extraction is handled by the caller (AuthController.getClientIpAddress).
 *
 * NOTE: IP-based limiting can be bypassed by spoofing X-Forwarded-For when
 * the application is directly exposed without a trusted reverse proxy. In
 * production, configure the load-balancer to be the only source of that header.
 */
@Slf4j
@Component
public class AuthRateLimiter {

    private static final String KEY_PREFIX = "auth:rate:";

    private final RedisTemplate<String, Object> redisTemplate;

    public AuthRateLimiter(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    // ── Public check methods ───────────────────────────────────────────────

    public void checkLogin(String ipAddress) {
        check("login", ipAddress, 10, Duration.ofMinutes(1),
                "Quá nhiều lần đăng nhập. Vui lòng thử lại sau 1 phút.");
    }

    public void checkForgotPassword(String ipAddress) {
        check("forgot-password", ipAddress, 3, Duration.ofMinutes(10),
                "Quá nhiều yêu cầu đặt lại mật khẩu. Vui lòng thử lại sau 10 phút.");
    }

    public void checkRegister(String ipAddress) {
        check("register", ipAddress, 5, Duration.ofMinutes(1),
                "Quá nhiều yêu cầu đăng ký. Vui lòng thử lại sau 1 phút.");
    }

    public void checkVerifyOtp(String ipAddress) {
        check("verify-otp", ipAddress, 5, Duration.ofMinutes(5),
                "Quá nhiều lần nhập OTP. Vui lòng thử lại sau 5 phút.");
    }

    // ── Core logic ────────────────────────────────────────────────────────

    private void check(String endpoint, String ipAddress, int maxRequests,
                       Duration window, String errorMessage) {
        String key = KEY_PREFIX + endpoint + ":" + sanitizeIp(ipAddress);
        try {
            Long count = redisTemplate.opsForValue().increment(key);
            if (count == null) return; // unexpected Redis null — fail open

            if (count == 1) {
                // First request in this window — set the TTL
                redisTemplate.expire(key, window);
            }

            if (count > maxRequests) {
                // Decrement so rejected requests do not consume quota
                redisTemplate.opsForValue().decrement(key);
                log.warn("Auth rate limit exceeded: endpoint={} ip={} count={} limit={}",
                        endpoint, ipAddress, count, maxRequests);
                throw new BusinessException("RATE_LIMIT_EXCEEDED", errorMessage);
            }

        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            // Redis failure — fail open to avoid blocking legitimate users
            log.warn("AuthRateLimiter: Redis error for endpoint={} ip={}: {}",
                    endpoint, ipAddress, e.getMessage());
        }
    }

    /**
     * Strip port and normalize IPv6 loopback to prevent key collisions.
     */
    private static String sanitizeIp(String ip) {
        if (ip == null) return "unknown";
        // IPv4-mapped IPv6 (::ffff:127.0.0.1) → normalize
        String normalized = ip.replaceFirst("^::ffff:", "");
        // Remove port if present (some proxies include it)
        int lastColon = normalized.lastIndexOf(':');
        if (lastColon > 0 && normalized.indexOf(':') == lastColon) {
            // single colon → IPv4:port format
            normalized = normalized.substring(0, lastColon);
        }
        return normalized;
    }
}
