package com.yunhwan.auth.error.infra.messaging.consumer.listener;

import com.yunhwan.auth.error.domain.consumer.ProcessedStatus;
import com.yunhwan.auth.error.domain.outbox.decision.OutboxDecision;
import com.yunhwan.auth.error.infra.metrics.MetricsConfig;
import com.yunhwan.auth.error.infra.support.HeaderUtils;
import com.yunhwan.auth.error.usecase.consumer.ConsumerDecisionMaker;
import com.yunhwan.auth.error.usecase.consumer.ConsumerRetryRequestRecorder;
import com.yunhwan.auth.error.usecase.consumer.port.ProcessedMessageStore;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.core.Message;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;

import static com.yunhwan.auth.error.infra.messaging.consumer.listener.ConsumerListenerSupport.consumeCounter;
import static com.yunhwan.auth.error.infra.messaging.consumer.listener.ConsumerListenerSupport.deadReason;
import static com.yunhwan.auth.error.infra.messaging.consumer.listener.ConsumerListenerSupport.dlqCounter;
import static com.yunhwan.auth.error.infra.messaging.consumer.listener.ConsumerListenerSupport.retryEnqueueCounter;

@Component
@RequiredArgsConstructor
public class ConsumerFlowSupport {

    private static final Duration LEASE_DURATION = Duration.ofSeconds(60);

    private final ProcessedMessageStore processedMessageStore;
    private final ConsumerDecisionMaker decisionMaker;
    private final ConsumerRetryRequestRecorder retryRequestRecorder;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    public HeaderValidationResult validateHeaders(Long outboxId, String eventType, String aggregateType) {
        if (outboxId == null) {
            return HeaderValidationResult.invalid(MetricsConfig.REASON_MISSING_OUTBOX_ID);
        }
        if (eventType == null || aggregateType == null) {
            return HeaderValidationResult.invalid(MetricsConfig.REASON_MISSING_HEADERS);
        }
        return HeaderValidationResult.ok();
    }

    public void recordReject(String queue, String eventType, String reason) {
        dlqCounter(meterRegistry, queue, eventType, reason).increment();
        consumeCounter(meterRegistry, queue, eventType, MetricsConfig.RESULT_REJECT).increment();
    }

    public OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    public ClaimResult claim(long outboxId, OffsetDateTime now) {
        processedMessageStore.ensureRowExists(outboxId, now);
        int claimed = processedMessageStore.claimProcessingUpdate(outboxId, now, now.plusSeconds(LEASE_DURATION.getSeconds()));
        if (claimed == 0) {
            return new ClaimResult(false, processedMessageStore.findStatusByOutboxId(outboxId).orElse(null));
        }
        return new ClaimResult(true, null);
    }

    public void markDone(String queue, String eventType, long outboxId, OffsetDateTime processedAt) {
        processedMessageStore.markDone(outboxId, processedAt);
        consumeCounter(meterRegistry, queue, eventType, MetricsConfig.RESULT_SUCCESS).increment();
    }

    public FailureResult handleFailure(
            String queue,
            String eventType,
            String aggregateType,
            String payload,
            long outboxId,
            Message message,
            OffsetDateTime now,
            Exception e
    ) {
        int currentRetrySafe = retryCountOrZero(message);
        OutboxDecision decision = decisionMaker.decide(now, currentRetrySafe, e);

        if (decision.isDead()) {
            processedMessageStore.markDead(outboxId, now, decision.lastError());
            consumeCounter(meterRegistry, queue, eventType, MetricsConfig.RESULT_DEAD).increment();
            dlqCounter(meterRegistry, queue, eventType, deadReason(e)).increment();
            return FailureResult.dead(decision);
        }

        retryRequestRecorder.recordRetryRequest(outboxId, eventType, aggregateType, payload, now, decision);
        consumeCounter(meterRegistry, queue, eventType, MetricsConfig.RESULT_RETRY).increment();
        retryEnqueueCounter(
                meterRegistry,
                queue,
                eventType,
                decision.nextRetryCount(),
                MetricsConfig.REASON_RETRYABLE
        ).increment();
        return FailureResult.retry(decision);
    }

    public int retryCount(Message message) {
        return HeaderUtils.getRetryCount(message.getMessageProperties().getHeaders());
    }

    private int retryCountOrZero(Message message) {
        try {
            return retryCount(message);
        } catch (Exception ignore) {
            return 0;
        }
    }

    public record HeaderValidationResult(boolean valid, String rejectReason) {
        static HeaderValidationResult ok() {
            return new HeaderValidationResult(true, null);
        }

        static HeaderValidationResult invalid(String reason) {
            return new HeaderValidationResult(false, reason);
        }
    }

    public record ClaimResult(boolean claimed, ProcessedStatus status) {
    }

    public record FailureResult(boolean dead, OutboxDecision decision) {
        static FailureResult dead(OutboxDecision decision) {
            return new FailureResult(true, decision);
        }

        static FailureResult retry(OutboxDecision decision) {
            return new FailureResult(false, decision);
        }
    }
}
