package com.fu.notification.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Configuration for async processing and scheduling in the notification module.
 */
@Configuration
@EnableAsync
@EnableScheduling
public class NotificationAsyncConfig {
    // Default Spring async executor is used
    // Custom thread pool can be configured here if needed
}
