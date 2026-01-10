package com.yunhwan.auth.error.outbox.scheduler;

import com.yunhwan.auth.error.config.outbox.OutboxProperties;
import com.yunhwan.auth.error.domain.outbox.OutboxMessage;
import com.yunhwan.auth.error.outbox.service.OutboxPoller;
import com.yunhwan.auth.error.outbox.service.OutboxProcessor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;

import java.util.List;

@Profile("!test")
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxPollerScheduler implements SchedulingConfigurer {

    private final OutboxPoller outboxPoller;
    private final OutboxProcessor processor;
    private final OutboxProperties props;
    private final TaskScheduler outboxTaskScheduler;

    @Override
    public void configureTasks(ScheduledTaskRegistrar taskRegistrar) {
        long delay = props.getPoller().getFixedDelayMs();

        taskRegistrar.setScheduler(outboxTaskScheduler);
        taskRegistrar.addFixedDelayTask(this::tick, delay);
    }

    void tick() {
        List<OutboxMessage> claimed = outboxPoller.pollOnce();

        if (!claimed.isEmpty()) {
            log.info("[outbox-poller] claimed {} messages. ids={}",
                    claimed.size(),
                    claimed.stream().map(OutboxMessage::getId).toList());
        }

        processor.process(claimed);
    }
}
