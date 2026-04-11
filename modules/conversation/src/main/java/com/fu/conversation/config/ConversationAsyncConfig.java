package com.fu.conversation.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Bounded thread pool for async tasks in the conversation module:
 * - RAGAS evaluation (fire-and-forget after each chat exchange)
 * - AI title generation (first message of a session)
 *
 * Prevents unbounded thread creation from default {@code @Async} executor
 * and keeps I/O-bound LLM calls off the CPU-bound ForkJoinPool.
 */
@Configuration
public class ConversationAsyncConfig {

    @Bean(name = "conversationExecutor")
    public Executor conversationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("conv-async-");
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
