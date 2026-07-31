package com.edutwin.shared.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class AsyncExecutionConfig {

    @Bean(name = "sseTaskExecutor")
    ThreadPoolTaskExecutor sseTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(32);
        executor.setQueueCapacity(256);
        executor.setThreadNamePrefix("edutwin-sse-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        return executor;
    }

    @Bean(name = "analysisTaskExecutor")
    ThreadPoolTaskExecutor analysisTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(5);
        executor.setMaxPoolSize(5);
        executor.setQueueCapacity(256);
        executor.setThreadNamePrefix("edutwin-analysis-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        return executor;
    }
}
