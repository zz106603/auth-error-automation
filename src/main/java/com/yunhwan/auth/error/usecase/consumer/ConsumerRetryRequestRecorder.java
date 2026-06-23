package com.yunhwan.auth.error.usecase.consumer;

import com.yunhwan.auth.error.domain.outbox.decision.OutboxDecision;
import com.yunhwan.auth.error.usecase.consumer.port.ProcessedMessageStore;
import com.yunhwan.auth.error.usecase.consumer.port.RetryPublishRequestStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

@Service
@RequiredArgsConstructor
public class ConsumerRetryRequestRecorder {

    private final ProcessedMessageStore processedMessageStore;
    private final RetryPublishRequestStore retryPublishRequestStore;

    @Transactional
    public void recordRetryRequest(
            long outboxId,
            String eventType,
            String aggregateType,
            String payload,
            OffsetDateTime now,
            OutboxDecision decision
    ) {
        processedMessageStore.markRetryWait(
                outboxId,
                now,
                decision.nextRetryAt(),
                decision.nextRetryCount(),
                decision.lastError()
        );

        retryPublishRequestStore.enqueue(
                outboxId,
                eventType,
                aggregateType,
                payload,
                decision.nextRetryCount(),
                decision.nextRetryAt(),
                decision.lastError(),
                now
        );
    }
}
