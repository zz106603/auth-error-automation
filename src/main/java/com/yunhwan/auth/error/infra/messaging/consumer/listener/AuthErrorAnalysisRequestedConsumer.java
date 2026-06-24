package com.yunhwan.auth.error.infra.messaging.consumer.listener;

import com.rabbitmq.client.Channel;
import com.yunhwan.auth.error.domain.consumer.ProcessedStatus;
import com.yunhwan.auth.error.domain.outbox.decision.OutboxDecision;
import com.yunhwan.auth.error.infra.metrics.MetricsConfig;
import com.yunhwan.auth.error.infra.messaging.consumer.parser.JacksonAuthErrorAnalysisRequestedPayloadParser;
import com.yunhwan.auth.error.infra.messaging.rabbit.RabbitTopologyConfig;
import com.yunhwan.auth.error.infra.support.HeaderUtils;
import com.yunhwan.auth.error.usecase.consumer.ConsumerDecisionMaker;
import com.yunhwan.auth.error.usecase.consumer.ConsumerRetryRequestRecorder;
import com.yunhwan.auth.error.usecase.consumer.handler.AuthErrorHandler;
import com.yunhwan.auth.error.usecase.consumer.port.ProcessedMessageStore;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;

import static com.yunhwan.auth.error.infra.messaging.consumer.listener.ConsumerListenerSupport.buildHeaders;
import static com.yunhwan.auth.error.infra.messaging.consumer.listener.ConsumerListenerSupport.consumeCounter;
import static com.yunhwan.auth.error.infra.messaging.consumer.listener.ConsumerListenerSupport.deadReason;
import static com.yunhwan.auth.error.infra.messaging.consumer.listener.ConsumerListenerSupport.dlqCounter;
import static com.yunhwan.auth.error.infra.messaging.consumer.listener.ConsumerListenerSupport.payloadSizeBytes;
import static com.yunhwan.auth.error.infra.messaging.consumer.listener.ConsumerListenerSupport.retryEnqueueCounter;

@Slf4j
@Component
public class AuthErrorAnalysisRequestedConsumer {

    private static final Duration LEASE_DURATION = Duration.ofSeconds(60);

    private final AuthErrorHandler handler;
    private final ProcessedMessageStore processedMessageStore;
    private final ConsumerDecisionMaker decisionMaker;
    private final ConsumerRetryRequestRecorder retryRequestRecorder;
    private final JacksonAuthErrorAnalysisRequestedPayloadParser payloadParser;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    public AuthErrorAnalysisRequestedConsumer(
            @Qualifier("authErrorAnalysisRequestedHandler") AuthErrorHandler handler,
            ProcessedMessageStore processedMessageStore,
            ConsumerDecisionMaker decisionMaker,
            ConsumerRetryRequestRecorder retryRequestRecorder,
            JacksonAuthErrorAnalysisRequestedPayloadParser payloadParser,
            MeterRegistry meterRegistry,
            Clock clock
    ) {
        this.handler = handler;
        this.processedMessageStore = processedMessageStore;
        this.decisionMaker = decisionMaker;
        this.retryRequestRecorder = retryRequestRecorder;
        this.payloadParser = payloadParser;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    @RabbitListener(queues = RabbitTopologyConfig.Q_ANALYSIS)
    public void onMessage(
            String payload,
            Message message,
            Channel channel,
            @Header(name = "outboxId", required = false) Long outboxId,
            @Header(name = "eventType", required = false) String eventType,
            @Header(name = "aggregateType", required = false) String aggregateType
    ) throws Exception {

        long tag = message.getMessageProperties().getDeliveryTag();

        if (outboxId == null) {
            log.warn("[AnalysisConsumer] missing outboxId -> reject(DLQ). payloadSizeBytes={}",
                    payloadSizeBytes(payload));
            // 사유 고정값으로 집계
            dlqCounter(meterRegistry, RabbitTopologyConfig.Q_ANALYSIS, eventType, MetricsConfig.REASON_MISSING_OUTBOX_ID).increment();
            consumeCounter(meterRegistry, RabbitTopologyConfig.Q_ANALYSIS, eventType, MetricsConfig.RESULT_REJECT).increment();
            channel.basicReject(tag, false);
            return;
        }

        if (eventType == null || aggregateType == null) {
            log.warn("[AnalysisConsumer] missing headers -> reject(DLQ). outboxId={}, eventType={}, aggregateType={}",
                    outboxId, eventType, aggregateType);
            // 헤더 계약 위반
            dlqCounter(meterRegistry, RabbitTopologyConfig.Q_ANALYSIS, eventType, MetricsConfig.REASON_MISSING_HEADERS).increment();
            consumeCounter(meterRegistry, RabbitTopologyConfig.Q_ANALYSIS, eventType, MetricsConfig.RESULT_REJECT).increment();
            channel.basicReject(tag, false);
            return;
        }

        try {
            payloadParser.parse(payload, outboxId);
        } catch (Exception e) {
            log.warn("[AnalysisConsumer] invalid payload -> reject(DLQ). outboxId={}, err={}", outboxId, e.getMessage());
            dlqCounter(meterRegistry, RabbitTopologyConfig.Q_ANALYSIS, eventType, MetricsConfig.REASON_INVALID_PAYLOAD).increment();
            consumeCounter(meterRegistry, RabbitTopologyConfig.Q_ANALYSIS, eventType, MetricsConfig.RESULT_REJECT).increment();
            channel.basicReject(tag, false);
            return;
        }

        OffsetDateTime now = OffsetDateTime.now(clock);
        OffsetDateTime leaseUntil = now.plusSeconds(LEASE_DURATION.getSeconds());

        processedMessageStore.ensureRowExists(outboxId, now);
        int claimed = processedMessageStore.claimProcessingUpdate(outboxId, now, leaseUntil);
        if (claimed == 0) {
            ProcessedStatus status = processedMessageStore.findStatusByOutboxId(outboxId).orElse(null);
            log.warn("[AnalysisConsumer] claim failed -> ack(drop). outboxId={}, status={}", outboxId, status);
            channel.basicAck(tag, false);
            return;
        }

        try {
            int currentRetry = HeaderUtils.getRetryCount(message.getMessageProperties().getHeaders());

            handler.handle(payload, buildHeaders(outboxId, eventType, aggregateType, currentRetry));

            processedMessageStore.markDone(outboxId, OffsetDateTime.now(clock));
            // consume_rate 기준선 (analysis)
            consumeCounter(meterRegistry, RabbitTopologyConfig.Q_ANALYSIS, eventType, MetricsConfig.RESULT_SUCCESS).increment();
            // E2E는 recorded 이벤트에서만 측정(중복 방지)
            channel.basicAck(tag, false);

        } catch (Exception e) {
            int currentRetrySafe;
            try {
                currentRetrySafe = HeaderUtils.getRetryCount(message.getMessageProperties().getHeaders());
            } catch (Exception ignore) {
                currentRetrySafe = 0;
            }

            OutboxDecision decision = decisionMaker.decide(now, currentRetrySafe, e);

            if (decision.isDead()) {
                processedMessageStore.markDead(outboxId, now, decision.lastError());
                log.error("[AnalysisConsumer] DEAD -> DLQ outboxId={}, err={}", outboxId, decision.lastError(), e);
                // DLQ 전환 집계
                consumeCounter(meterRegistry, RabbitTopologyConfig.Q_ANALYSIS, eventType, MetricsConfig.RESULT_DEAD).increment();
                dlqCounter(meterRegistry, RabbitTopologyConfig.Q_ANALYSIS, eventType, deadReason(e)).increment();
                channel.basicReject(tag, false);
                return;
            }

            retryRequestRecorder.recordRetryRequest(
                    outboxId,
                    eventType,
                    aggregateType,
                    payload,
                    now,
                    decision
            );

            log.warn("[AnalysisConsumer] RETRY -> outboxId={}, nextRetryCount={}, nextRetryAt={}, err={}",
                    outboxId, decision.nextRetryCount(), decision.nextRetryAt(), decision.lastError());

            // retry_enqueue_rate 분자 (analysis)
            consumeCounter(meterRegistry, RabbitTopologyConfig.Q_ANALYSIS, eventType, MetricsConfig.RESULT_RETRY).increment();
            retryEnqueueCounter(meterRegistry, RabbitTopologyConfig.Q_ANALYSIS, eventType, decision.nextRetryCount(), MetricsConfig.REASON_RETRYABLE).increment();

            channel.basicAck(tag, false);
        }
    }
}
