package com.yunhwan.auth.error.outbox.scheduler;

import com.yunhwan.auth.error.domain.outbox.OutboxMessage;
import com.yunhwan.auth.error.outbox.service.OutboxPoller;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxPollerScheduler {

    private final OutboxPoller outboxPoller;

    @Scheduled(fixedDelayString = "${outbox.poller.fixed-delay-ms:500}")
    public void tick() {
        List<OutboxMessage> claimed = outboxPoller.pollOnce();

        if (!claimed.isEmpty()) {
            log.info("[outbox-poller] claimed {} messages. ids={}",
                    claimed.size(),
                    claimed.stream().map(OutboxMessage::getId).toList());
        }
    }
}