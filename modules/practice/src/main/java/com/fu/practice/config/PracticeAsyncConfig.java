package com.fu.practice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Provides a dedicated virtual-thread executor for practice LLM calls.
 *
 * Why virtual threads:
 * - LLM calls are pure I/O — they block waiting for network bytes.
 * - Platform thread pools stall when all threads are blocked on I/O.
 * - Java 21 virtual threads are mounted/unmounted by the JVM on each I/O
 *   syscall, so 20 concurrent LLM calls consume 20 virtual threads (cheap)
 *   instead of 20 OS threads (expensive / pool-limited).
 * - No pool sizing to tune — each submitted task gets its own virtual thread.
 *
 * Result: N parallel LLM calls → wall time ≈ 1 LLM call, regardless of N.
 */
@Configuration
public class PracticeAsyncConfig {

    @Bean(name = "practiceTaskExecutor")
    public Executor practiceTaskExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
