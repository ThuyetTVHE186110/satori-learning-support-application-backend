package com.fu.curriculumimport.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Async and scheduling configuration for curriculum import processing.
 * Provides a dedicated thread pool for import jobs and enables scheduling
 * for the retry scheduler.
 */
@Configuration
@EnableAsync
@EnableScheduling
@Slf4j
@RequiredArgsConstructor
public class AsyncImportConfig {

    private final AgenticRagProperties ragProperties;

    /**
     * Thread pool executor for import processing tasks.
     * Values are configurable via application.yml under curriculum-import.agentic-rag.
     * CallerRunsPolicy ensures tasks are never silently dropped when the queue is full.
     */
    @Bean(name = "importTaskExecutor")
    public Executor importTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(ragProperties.getThreadPoolCoreSize());
        executor.setMaxPoolSize(ragProperties.getThreadPoolMaxSize());
        executor.setQueueCapacity(ragProperties.getThreadPoolQueueCapacity());
        executor.setThreadNamePrefix("import-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();

        log.info("Import thread pool initialized — core={}, max={}, queue={}",
                ragProperties.getThreadPoolCoreSize(),
                ragProperties.getThreadPoolMaxSize(),
                ragProperties.getThreadPoolQueueCapacity());
        return executor;
    }
}
