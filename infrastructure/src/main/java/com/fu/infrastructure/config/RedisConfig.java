package com.fu.infrastructure.config;

import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Redis Configuration for caching AI responses, user sessions, and learning progress.
 * 
 * Cache Categories:
 * - AI_RESPONSE: Cache AI-generated responses (5 minutes TTL)
 * - USER_SESSION: User session data (1 hour TTL)
 * - LESSON_DATA: Static lesson content (24 hours TTL)
 * - VOCABULARY: Vocabulary data (24 hours TTL)
 * - USER_PROGRESS: User learning progress (30 minutes TTL)
 */
@Configuration
@EnableCaching
public class RedisConfig {

    // Cache names
    public static final String CACHE_AI_RESPONSE = "ai_response";
    public static final String CACHE_USER_SESSION = "user_session";
    public static final String CACHE_LESSON_DATA = "lesson_data";
    public static final String CACHE_VOCABULARY = "vocabulary";
    public static final String CACHE_USER_PROGRESS = "user_progress";
    public static final String CACHE_PERSONALIZATION = "personalization";
    public static final String CACHE_ROLEPLAY_HISTORY = "roleplay_history";
    public static final String CACHE_ROLEPLAY_SESSION = "roleplay_session";

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        
        // Use String serializer for keys
        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        
        // Use JSON serializer for values
        GenericJackson2JsonRedisSerializer jsonSerializer = new GenericJackson2JsonRedisSerializer();
        template.setValueSerializer(jsonSerializer);
        template.setHashValueSerializer(jsonSerializer);
        
        template.afterPropertiesSet();
        return template;
    }

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        // Default cache configuration
        RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofHours(1))
                .serializeKeysWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new GenericJackson2JsonRedisSerializer()))
                .disableCachingNullValues();

        // Custom TTL for different cache types
        Map<String, RedisCacheConfiguration> cacheConfigurations = new HashMap<>();
        
        // AI Response cache - short TTL (5 minutes) to balance freshness and cost
        cacheConfigurations.put(CACHE_AI_RESPONSE, defaultConfig.entryTtl(Duration.ofMinutes(5)));
        
        // User session cache - 1 hour TTL
        cacheConfigurations.put(CACHE_USER_SESSION, defaultConfig.entryTtl(Duration.ofHours(1)));
        
        // Lesson data cache - 24 hours TTL (static content)
        cacheConfigurations.put(CACHE_LESSON_DATA, defaultConfig.entryTtl(Duration.ofHours(24)));
        
        // Vocabulary cache - 24 hours TTL (static content)
        cacheConfigurations.put(CACHE_VOCABULARY, defaultConfig.entryTtl(Duration.ofHours(24)));
        
        // User progress cache - 30 minutes TTL
        cacheConfigurations.put(CACHE_USER_PROGRESS, defaultConfig.entryTtl(Duration.ofMinutes(30)));
        
        // Personalization recommendations cache - 15 minutes TTL
        cacheConfigurations.put(CACHE_PERSONALIZATION, defaultConfig.entryTtl(Duration.ofMinutes(15)));

        // Roleplay history cache - 2 hours TTL
        cacheConfigurations.put(CACHE_ROLEPLAY_HISTORY, defaultConfig.entryTtl(Duration.ofHours(2)));

        // Roleplay session cache - 1 hour TTL
        cacheConfigurations.put(CACHE_ROLEPLAY_SESSION, defaultConfig.entryTtl(Duration.ofHours(1)));

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaultConfig)
                .withInitialCacheConfigurations(cacheConfigurations)
                .transactionAware()
                .build();
    }
}
