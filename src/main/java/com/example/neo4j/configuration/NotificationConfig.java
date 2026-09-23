package com.example.neo4j.configuration;

import java.util.concurrent.Executor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

// Emails are sent on a small, bounded background pool so a slow mail server never holds up requests.
@Configuration
@EnableAsync
public class NotificationConfig {

    public static final String NOTIFICATION_EXECUTOR = "notificationExecutor";

    @Bean(NOTIFICATION_EXECUTOR)
    Executor notificationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("notify-");
        executor.initialize();
        return executor;
    }
}
