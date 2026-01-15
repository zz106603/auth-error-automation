package com.yunhwan.auth.error.infra.scheduling.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
public class OutboxSchedulingConfig {

    @Bean
    public TaskScheduler outboxTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2); // poller + reaper
        scheduler.setThreadNamePrefix("outbox-");
        scheduler.initialize();
        return scheduler;
    }
}
