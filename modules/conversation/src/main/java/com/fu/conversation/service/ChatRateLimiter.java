package com.fu.conversation.service;

import com.fu.common.exception.BusinessException;
import com.fu.conversation.config.ChatProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

/**
 * Per-user rate limiter for the CURRICULUM_QA chatbot.
 *
 * Uses a fixed-window Redis counter: the key is created with a TTL on the first
 * request of each window. Redis INCR is atomic, so concurrent requests are
 * handled correctly without application-level locking.
 *
 * When the limit is exceeded the counter is decremented so it stays accurate
 * for the rest of the window (rejected requests do not consume quota).
 *
 * Fail-open design: if Redis is unreachable the request is allowed
 * through (rate limiting is best-effort, not a hard security boundary).
 */
@Component
@Slf4j
public class ChatRateLimiter {

    private static final String KEY_PREFIX = "chat:rate:";

    private final RedisTemplate<String, Object> redisTemplate;
    private final ChatProperties chatProperties;

    public ChatRateLimiter(RedisTemplate<String, Object> redisTemplate,
                           ChatProperties chatProperties) {
        this.redisTemplate = redisTemplate;
        this.chatProperties = chatProperties;
    }

    /**
     * Check whether the user has exceeded their per-window message quota.
     *
     * @param userId authenticated user ID
     * @throws BusinessException with code RATE_LIMIT_EXCEEDED when over quota
     */
    public void checkRateLimit(UUID userId) {
        String key = KEY_PREFIX + userId;
        try {
            Long count = redisTemplate.opsForValue().increment(key);
            if (count == null) return; // Redis returned unexpected null — fail open

            // Set expiry once on the first request of a new window
            if (count == 1) {
                redisTemplate.expire(key, Duration.ofMinutes(chatProperties.getRateLimitWindowMinutes()));
            }

            if (count > chatProperties.getRateLimitMaxRequests()) {
                // Decrement so rejected requests don't consume quota in this window
                redisTemplate.opsForValue().decrement(key);
                log.warn("Rate limit exceeded: userId={} limit={} window={}m",
                        userId,
                        chatProperties.getRateLimitMaxRequests(),
                        chatProperties.getRateLimitWindowMinutes());
                throw new BusinessException("RATE_LIMIT_EXCEEDED",
                        "Bạn đã gửi quá nhiều tin nhắn. Vui lòng đợi một lúc trước khi tiếp tục.");
            }

        } catch (BusinessException e) {
            throw e; // propagate rate-limit rejection
        } catch (Exception e) {
            // Redis failure → fail open so users are not blocked by infrastructure issues
            log.warn("ChatRateLimiter: Redis error for userId={}, skipping rate check: {}",
                    userId, e.getMessage());
        }
    }
}
