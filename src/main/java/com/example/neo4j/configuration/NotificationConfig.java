package com.example.neo4j.configuration;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

// Runs the email outbox dispatcher (outbox.OutboxDispatcher) on a schedule.
@Configuration
@EnableScheduling
public class NotificationConfig {
}
