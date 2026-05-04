package com.fu.assignment.ai.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Provides a dedicated virtual-thread executor for quiz AI batch calls.
 *
 * Why virtual threads:
 * - Each batch is a pure I/O call waiting for OpenAI network bytes.
 * - Virtual threads are mounted/unmounted by the JVM on each I/O syscall,
 *   so N parallel batches consume N virtual threads (cheap) instead of
 *   N OS threads (expensive / pool-limited).
 * - No pool sizing needed — each batch gets its own virtual thread.
 *
 * Result: 13 batches (50 questions) run in parallel → wall time ≈ 1 batch.
 */
@Configuration
public class QuizAsyncConfig {

    @Bean("quizBatchExecutor")
    public Executor quizBatchExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
