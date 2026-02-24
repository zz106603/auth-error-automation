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
        scheduler.setPoolSize(4); // 부하 테스트 지표 스케줄러 포함
        scheduler.setThreadNamePrefix("outbox-");
        scheduler.initialize();
        return scheduler;
    }
}
