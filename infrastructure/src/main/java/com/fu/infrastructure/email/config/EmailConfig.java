package com.fu.infrastructure.email.config;

import com.resend.Resend;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.templateresolver.SpringResourceTemplateResolver;
import org.thymeleaf.templatemode.TemplateMode;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Email configuration using Resend HTTP API + Thymeleaf templates.
 */
@Configuration
@EnableAsync
public class EmailConfig {

    @Bean
    public Resend resend(@Value("${app.email.resend-api-key:}") String apiKey) {
        return new Resend(apiKey);
    }

    @Bean
    public SpringResourceTemplateResolver emailTemplateResolver() {
        SpringResourceTemplateResolver resolver = new SpringResourceTemplateResolver();
        resolver.setPrefix("classpath:/templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding(StandardCharsets.UTF_8.name());
        resolver.setCacheable(true);
        resolver.setOrder(1);
        resolver.setCheckExistence(true);
        return resolver;
    }

    @Bean
    public SpringTemplateEngine emailTemplateEngine(SpringResourceTemplateResolver emailTemplateResolver) {
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(emailTemplateResolver);
        engine.setEnableSpringELCompiler(true);
        return engine;
    }

    /**
     * Single-thread executor for all email sends.
     * Resend's free tier is 2 req/s — serializing here lets the in-method 600 ms
     * throttle in {@code EmailServiceImpl.sendThrottled} actually take effect.
     * A multi-thread executor (the previous silent default) let bursty traffic
     * fire concurrent sends and trip Resend's rate limiter.
     *
     * <p>Bounded queue (200) + CallerRunsPolicy: under sustained burst, the calling
     * thread takes the work instead of dropping the email or running OOM.</p>
     */
    @Bean(name = "mailExecutor")
    public TaskExecutor mailExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("mail-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
