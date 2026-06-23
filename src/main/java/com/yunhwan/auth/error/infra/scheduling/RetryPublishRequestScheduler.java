package com.yunhwan.auth.error.infra.scheduling;

import com.yunhwan.auth.error.usecase.consumer.RetryPublishRequestClaimResult;
import com.yunhwan.auth.error.usecase.consumer.RetryPublishRequestPoller;
import com.yunhwan.auth.error.usecase.consumer.RetryPublishRequestProcessor;
import com.yunhwan.auth.error.usecase.outbox.config.OutboxProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;

@Profile("!test")
@Slf4j
@Component
@RequiredArgsConstructor
public class RetryPublishRequestScheduler implements SchedulingConfigurer {

    private final RetryPublishRequestPoller poller;
    private final RetryPublishRequestProcessor processor;
    private final OutboxProperties props;
    private final TaskScheduler outboxTaskScheduler;

    @Override
    public void configureTasks(ScheduledTaskRegistrar taskRegistrar) {
        taskRegistrar.setScheduler(outboxTaskScheduler);
        taskRegistrar.addFixedDelayTask(this::tick, props.getPoller().getFixedDelayMs());
    }

    void tick() {
        RetryPublishRequestClaimResult result = poller.pollOnce();
        if (result.claimed().isEmpty()) {
            return;
        }
        log.info("[retry-publish-poller] owner={} claimed {} requests",
                result.owner(), result.claimed().size());
        processor.process(result.owner(), result.claimed());
    }
}
