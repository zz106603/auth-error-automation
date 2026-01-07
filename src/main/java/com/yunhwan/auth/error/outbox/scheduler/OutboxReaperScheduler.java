package com.yunhwan.auth.error.outbox.scheduler;

import com.yunhwan.auth.error.config.outbox.OutboxProperties;
import com.yunhwan.auth.error.outbox.service.OutboxReaper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxReaperScheduler implements SchedulingConfigurer {

    private final OutboxReaper reaper;
    private final OutboxProperties props;
    private final TaskScheduler outboxTaskScheduler;

    @Override
    public void configureTasks(ScheduledTaskRegistrar taskRegistrar) {
        long delay = props.getReaper().getFixedDelayMs();

        taskRegistrar.setScheduler(outboxTaskScheduler);
        taskRegistrar.addFixedDelayTask(this::tick, delay);
    }

    void tick() {
        int reaped = reaper.reapOnce();
        if (reaped > 0) {
            log.warn("[outbox-reaper] reaped {} stale PROCESSING messages", reaped);
        }
    }
}
