package com.fu.assignment.ai.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
public class QuizAsyncConfig {

    @Bean("quizBatchExecutor")
    public Executor quizBatchExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);      // 2 batches in parallel
        executor.setMaxPoolSize(4);       // Max 4 threads if load increases
        executor.setQueueCapacity(50);    // Queue up to 50 tasks
        executor.setThreadNamePrefix("quiz-batch-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }
}
