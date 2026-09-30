package com.smartstaff.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Async task execution configuration for long-running operations like role profile extraction.
 */
@Configuration
@EnableAsync
public class AsyncExecutorConfig {

    @Bean(name = "profileExtractorExecutor")
    public Executor profileExtractorExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(5);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("role-profile-");
        executor.initialize();
        return executor;
    }
}
