package com.smartstaff.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
@EnableAsync
public class AsyncExecutorConfig {

    /** app.async.inline=true runs background work on the calling thread —
     *  used by the integration tests so stubbed calls happen deterministically. */
    @Bean(name = "profileExtractorExecutor")
    public Executor profileExtractorExecutor(@Value("${app.async.inline:false}") boolean inline) {
        if (inline) return new SyncTaskExecutor();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(5);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("role-profile-");
        executor.initialize();
        return executor;
    }

    /** Question-set generation: long-running (many Gemini and code-runner calls). */
    @Bean(name = "assessmentExecutor")
    public Executor assessmentExecutor(@Value("${app.async.inline:false}") boolean inline) {
        if (inline) return new SyncTaskExecutor();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("assessment-gen-");
        executor.initialize();
        return executor;
    }
}
