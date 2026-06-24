package com.yunhwan.auth.error.infra.messaging.consumer.listener;

import com.rabbitmq.client.Channel;
import com.yunhwan.auth.error.domain.consumer.ProcessedStatus;
import com.yunhwan.auth.error.domain.outbox.decision.OutboxDecision;
import com.yunhwan.auth.error.infra.metrics.MetricsConfig;
import com.yunhwan.auth.error.infra.metrics.RecordedConsumerMetricsContext;
import com.yunhwan.auth.error.infra.messaging.rabbit.RabbitTopologyConfig;
import com.yunhwan.auth.error.infra.support.HeaderUtils;
import com.yunhwan.auth.error.usecase.autherror.dto.AuthErrorRecordedPayload;
import com.yunhwan.auth.error.usecase.consumer.ConsumerDecisionMaker;
import com.yunhwan.auth.error.usecase.consumer.ConsumerRetryRequestRecorder;
import com.yunhwan.auth.error.usecase.consumer.handler.AuthErrorHandler;
import com.yunhwan.auth.error.usecase.consumer.port.AuthErrorPayloadParser;
import com.yunhwan.auth.error.usecase.consumer.port.ProcessedMessageStore;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.TimeUnit;

import static com.yunhwan.auth.error.infra.messaging.consumer.listener.ConsumerListenerSupport.buildHeaders;
import static com.yunhwan.auth.error.infra.messaging.consumer.listener.ConsumerListenerSupport.consumeCounter;
import static com.yunhwan.auth.error.infra.messaging.consumer.listener.ConsumerListenerSupport.deadReason;
import static com.yunhwan.auth.error.infra.messaging.consumer.listener.ConsumerListenerSupport.dlqCounter;
import static com.yunhwan.auth.error.infra.messaging.consumer.listener.ConsumerListenerSupport.eventTypeOrUnknown;
import static com.yunhwan.auth.error.infra.messaging.consumer.listener.ConsumerListenerSupport.payloadSizeBytes;
import static com.yunhwan.auth.error.infra.messaging.consumer.listener.ConsumerListenerSupport.retryEnqueueCounter;

@Slf4j
@Component
public class AuthErrorRecordedConsumer {

    private static final Duration LEASE_DURATION = Duration.ofSeconds(60);

    private final AuthErrorHandler handler;
    private final ProcessedMessageStore processedMessageStore;
    private final ConsumerDecisionMaker decisionMaker;
    private final ConsumerRetryRequestRecorder retryRequestRecorder;
    private final AuthErrorPayloadParser payloadParser;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    public AuthErrorRecordedConsumer(
            @Qualifier("authErrorRecordedHandler") AuthErrorHandler handler,
            ProcessedMessageStore processedMessageStore,
            ConsumerDecisionMaker decisionMaker,
            ConsumerRetryRequestRecorder retryRequestRecorder,
            AuthErrorPayloadParser payloadParser,
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

    @RabbitListener(queues = RabbitTopologyConfig.Q_RECORDED)
    public void onMessage(
            String payload,
            Message message,
            Channel channel,
            @Header(name = "outboxId", required = false) Long outboxId,
            @Header(name = "eventType", required = false) String eventType,
            @Header(name = "aggregateType", required = false) String aggregateType
    ) throws Exception {

        long tag = message.getMessageProperties().getDeliveryTag();

        // 1) outboxId 없으면 형식 불량 → DLQ 격리
        if (outboxId == null) {
            log.warn("[AuthErrorConsumer] missing outboxId -> reject(DLQ). payloadSizeBytes={}",
                    payloadSizeBytes(payload));
            // 사유 고정값으로 집계
            dlqCounter(meterRegistry, RabbitTopologyConfig.Q_RECORDED, eventType, MetricsConfig.REASON_MISSING_OUTBOX_ID).increment();
            // 유효성 실패는 consume reject로 집계
            consumeCounter(meterRegistry, RabbitTopologyConfig.Q_RECORDED, eventType, MetricsConfig.RESULT_REJECT).increment();
            channel.basicReject(tag, false);
            return;
        }

        if (eventType == null || aggregateType == null) {
            log.warn("[AuthErrorConsumer] missing headers -> reject(DLQ). outboxId={}, eventType={}, aggregateType={}",
                    outboxId, eventType, aggregateType);
            // 헤더 계약 위반
            dlqCounter(meterRegistry, RabbitTopologyConfig.Q_RECORDED, eventType, MetricsConfig.REASON_MISSING_HEADERS).increment();
            consumeCounter(meterRegistry, RabbitTopologyConfig.Q_RECORDED, eventType, MetricsConfig.RESULT_REJECT).increment();
            channel.basicReject(tag, false);
            return;
        }

        // 1.5) payload 파싱 실패는 즉시 DLQ (부작용 없이)
        AuthErrorRecordedPayload parsed;
        try {
            parsed = payloadParser.parse(payload, outboxId);
        } catch (Exception e) {
            log.warn("[AuthErrorConsumer] invalid payload -> reject(DLQ). outboxId={}, err={}", outboxId, e.getMessage());
            // payload 계약 위반
            dlqCounter(meterRegistry, RabbitTopologyConfig.Q_RECORDED, eventType, MetricsConfig.REASON_INVALID_PAYLOAD).increment();
            consumeCounter(meterRegistry, RabbitTopologyConfig.Q_RECORDED, eventType, MetricsConfig.RESULT_REJECT).increment();
            channel.basicReject(tag, false);
            return;
        }

        OffsetDateTime now = OffsetDateTime.now(clock);
        OffsetDateTime leaseUntil = now.plusSeconds(LEASE_DURATION.getSeconds());

        try (RecordedConsumerMetricsContext.Scope ignored =
                     RecordedConsumerMetricsContext.open(eventType, RabbitTopologyConfig.Q_RECORDED)) {
            // 2) 선점
            long claimSetupStartedAt = System.nanoTime();
            int claimed;
            try {
                processedMessageStore.ensureRowExists(outboxId, now);
                claimed = processedMessageStore.claimProcessingUpdate(outboxId, now, leaseUntil);
                if (claimed == 0) {
                    ProcessedStatus status = processedMessageStore.findStatusByOutboxId(outboxId).orElse(null);
                    log.warn("[AuthErrorConsumer] claim failed -> ack(drop). outboxId={}, status={}", outboxId, status);
                    channel.basicAck(tag, false);
                    return;
                }
            } finally {
                recordStageTimer(
                        MetricsConfig.METRIC_RECORDED_CONSUMER_CLAIM_SETUP_TOTAL,
                        eventType,
                        System.nanoTime() - claimSetupStartedAt
                );
            }

            try {
                int currentRetry = HeaderUtils.getRetryCount(message.getMessageProperties().getHeaders());

                long handlerStartedAt = System.nanoTime();
                try {
                    // 3) handler 위임
                    handler.handle(payload, buildHeaders(outboxId, eventType, aggregateType, currentRetry));
                } finally {
                    recordStageTimer(
                            MetricsConfig.METRIC_RECORDED_CONSUMER_HANDLER_TOTAL,
                            eventType,
                            System.nanoTime() - handlerStartedAt
                    );
                }

                long completionStartedAt = System.nanoTime();
                try {
                    // 4) 성공 확정
                    OffsetDateTime processedAt = OffsetDateTime.now(clock);
                    processedMessageStore.markDone(outboxId, processedAt);
                    recordLatencyMetrics(eventType, parsed, MetricsConfig.RESULT_SUCCESS, processedAt);
                    // consume_rate 기준선
                    consumeCounter(meterRegistry, RabbitTopologyConfig.Q_RECORDED, eventType, MetricsConfig.RESULT_SUCCESS).increment();

                    channel.basicAck(tag, false);
                } finally {
                    recordStageTimer(
                            MetricsConfig.METRIC_RECORDED_CONSUMER_POST_HANDLER_COMPLETION_TOTAL,
                            eventType,
                            System.nanoTime() - completionStartedAt
                    );
                }
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
                    recordLatencyMetrics(eventType, parsed, MetricsConfig.RESULT_DEAD, now);

                    log.error("[AuthErrorConsumer] DEAD -> DLQ outboxId={}, err={}", outboxId, decision.lastError(), e);
                    // DLQ 전환 집계
                    consumeCounter(meterRegistry, RabbitTopologyConfig.Q_RECORDED, eventType, MetricsConfig.RESULT_DEAD).increment();
                    dlqCounter(meterRegistry, RabbitTopologyConfig.Q_RECORDED, eventType, deadReason(e)).increment();
                    // DLQ 격리
                    channel.basicReject(tag, false);
                    return;
                }

                // 실패 기록과 retry publish request 저장은 같은 DB 트랜잭션에서 수행한다.
                retryRequestRecorder.recordRetryRequest(
                        outboxId,
                        eventType,
                        aggregateType,
                        payload,
                        now,
                        decision
                );

                log.warn("[AuthErrorConsumer] RETRY -> outboxId={}, nextRetryCount={}, nextRetryAt={}, err={}",
                        outboxId, decision.nextRetryCount(), decision.nextRetryAt(), decision.lastError());

                // retry_enqueue_rate 분자
                consumeCounter(meterRegistry, RabbitTopologyConfig.Q_RECORDED, eventType, MetricsConfig.RESULT_RETRY).increment();
                retryEnqueueCounter(meterRegistry, RabbitTopologyConfig.Q_RECORDED, eventType, decision.nextRetryCount(), MetricsConfig.REASON_RETRYABLE).increment();

                // 원본 메시지 ACK
                channel.basicAck(tag, false);
            }
        }
    }

    private void recordLatencyMetrics(String eventType,
                                      AuthErrorRecordedPayload payload,
                                      String result,
                                      OffsetDateTime processedAt) {
        if (payload == null || processedAt == null) return;
        recordTimer(MetricsConfig.METRIC_CLIENT_EVENT_TO_CONSUME, eventType, result, payload.occurredAt(), processedAt);
        recordTimer(MetricsConfig.METRIC_INGEST_TO_CONSUME, eventType, result, payload.receivedAt(), processedAt);
    }

    private void recordTimer(String metricName,
                             String eventType,
                             String result,
                             OffsetDateTime startedAt,
                             OffsetDateTime endedAt) {
        if (startedAt == null || endedAt == null) return;
        long ms = ChronoUnit.MILLIS.between(startedAt, endedAt);
        if (ms < 0) return;
        Timer.builder(metricName)
                .tag(MetricsConfig.TAG_EVENT_TYPE, eventType)
                .tag(MetricsConfig.TAG_QUEUE, RabbitTopologyConfig.Q_RECORDED)
                .tag(MetricsConfig.TAG_RESULT, result)
                .register(meterRegistry)
                .record(ms, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    private void recordStageTimer(String metricName, String eventType, long durationNanos) {
        if (durationNanos < 0) return;
        Timer.builder(metricName)
                .tag(MetricsConfig.TAG_EVENT_TYPE, eventTypeOrUnknown(eventType))
                .tag(MetricsConfig.TAG_QUEUE, RabbitTopologyConfig.Q_RECORDED)
                .register(meterRegistry)
                .record(durationNanos, TimeUnit.NANOSECONDS);
    }
}
