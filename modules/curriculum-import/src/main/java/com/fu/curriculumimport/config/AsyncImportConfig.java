package com.fu.curriculumimport.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Async and scheduling configuration for curriculum import processing.
 * Provides a dedicated thread pool for import jobs and enables scheduling
 * for the retry scheduler.
 */
@Configuration
@EnableAsync
@EnableScheduling
@Slf4j
public class AsyncImportConfig {

    /**
     * Thread pool executor for import processing tasks.
     * - Core pool: 2 threads (concurrent imports)
     * - Max pool: 4 threads (burst capacity)
     * - Queue: 10 (pending imports)
     */
    @Bean(name = "importTaskExecutor")
    public Executor importTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(10);
        executor.setThreadNamePrefix("import-");
        executor.setRejectedExecutionHandler((r, e) ->
                log.warn("Import task rejected - queue full. Consider increasing capacity."));
        executor.initialize();
        return executor;
    }
}
