package com.yunhwan.auth.error.infra.messaging.consumer.listener;

import com.rabbitmq.client.Channel;
import com.yunhwan.auth.error.common.exception.RetryableAuthErrorException;
import com.yunhwan.auth.error.infra.metrics.MetricsConfig;
import com.yunhwan.auth.error.infra.metrics.RecordedConsumerMetricsContext;
import com.yunhwan.auth.error.infra.messaging.rabbit.RabbitTopologyConfig;
import com.yunhwan.auth.error.usecase.autherror.dto.AuthErrorRecordedPayload;
import com.yunhwan.auth.error.usecase.consumer.handler.AuthErrorHandler;
import com.yunhwan.auth.error.usecase.consumer.port.AuthErrorPayloadParser;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.TimeUnit;

import static com.yunhwan.auth.error.infra.messaging.consumer.listener.ConsumerListenerSupport.buildHeaders;
import static com.yunhwan.auth.error.infra.messaging.consumer.listener.ConsumerListenerSupport.eventTypeOrUnknown;
import static com.yunhwan.auth.error.infra.messaging.consumer.listener.ConsumerListenerSupport.payloadSizeBytes;

@Slf4j
@Component
public class AuthErrorRecordedConsumer {

    private final AuthErrorHandler handler;
    private final AuthErrorPayloadParser payloadParser;
    private final ConsumerFlowSupport flowSupport;
    private final MeterRegistry meterRegistry;
    private final ConsumerDelayProperties delayProperties;
    private final ConsumerFailureInjectionProperties failureInjectionProperties;

    public AuthErrorRecordedConsumer(
            @Qualifier("authErrorRecordedHandler") AuthErrorHandler handler,
            AuthErrorPayloadParser payloadParser,
            ConsumerFlowSupport flowSupport,
            MeterRegistry meterRegistry,
            ConsumerDelayProperties delayProperties,
            ConsumerFailureInjectionProperties failureInjectionProperties
    ) {
        this.handler = handler;
        this.payloadParser = payloadParser;
        this.flowSupport = flowSupport;
        this.meterRegistry = meterRegistry;
        this.delayProperties = delayProperties;
        this.failureInjectionProperties = failureInjectionProperties;
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

        // 1) outboxId 없으면 형식 불량 -> DLQ 격리
        ConsumerFlowSupport.HeaderValidationResult headerValidation =
                flowSupport.validateHeaders(outboxId, eventType, aggregateType);
        if (!headerValidation.valid() && outboxId == null) {
            log.warn("[AuthErrorConsumer] missing outboxId -> reject(DLQ). payloadSizeBytes={}",
                    payloadSizeBytes(payload));
            flowSupport.recordReject(RabbitTopologyConfig.Q_RECORDED, eventType, headerValidation.rejectReason());
            channel.basicReject(tag, false);
            return;
        }

        if (!headerValidation.valid()) {
            log.warn("[AuthErrorConsumer] missing headers -> reject(DLQ). outboxId={}, eventType={}, aggregateType={}",
                    outboxId, eventType, aggregateType);
            flowSupport.recordReject(RabbitTopologyConfig.Q_RECORDED, eventType, headerValidation.rejectReason());
            channel.basicReject(tag, false);
            return;
        }

        // 1.5) payload 파싱 실패는 즉시 DLQ (부작용 없이)
        AuthErrorRecordedPayload parsed;
        try {
            parsed = payloadParser.parse(payload, outboxId);
        } catch (Exception e) {
            log.warn("[AuthErrorConsumer] invalid payload -> reject(DLQ). outboxId={}, err={}", outboxId, e.getMessage());
            flowSupport.recordReject(RabbitTopologyConfig.Q_RECORDED, eventType, MetricsConfig.REASON_INVALID_PAYLOAD);
            channel.basicReject(tag, false);
            return;
        }

        OffsetDateTime now = flowSupport.now();

        try (RecordedConsumerMetricsContext.Scope ignored =
                     RecordedConsumerMetricsContext.open(eventType, RabbitTopologyConfig.Q_RECORDED)) {
            // 2) 선점
            long claimSetupStartedAt = System.nanoTime();
            ConsumerFlowSupport.ClaimResult claimResult;
            try {
                claimResult = flowSupport.claim(outboxId, now);
                if (!claimResult.claimed()) {
                    log.warn("[AuthErrorConsumer] claim failed -> ack(drop). outboxId={}, status={}", outboxId, claimResult.status());
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

            applyLoadTestDelay(outboxId, eventType);

            try {
                int currentRetry = flowSupport.retryCount(message);

                long handlerStartedAt = System.nanoTime();
                try {
                    maybeInjectLoadTestFailure(outboxId, eventType, parsed, currentRetry);

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
                    OffsetDateTime processedAt = flowSupport.now();
                    flowSupport.markDone(RabbitTopologyConfig.Q_RECORDED, eventType, outboxId, processedAt);
                    recordLatencyMetrics(eventType, parsed, MetricsConfig.RESULT_SUCCESS, processedAt);

                    channel.basicAck(tag, false);
                } finally {
                    recordStageTimer(
                            MetricsConfig.METRIC_RECORDED_CONSUMER_POST_HANDLER_COMPLETION_TOTAL,
                            eventType,
                            System.nanoTime() - completionStartedAt
                    );
                }
            } catch (Exception e) {
                ConsumerFlowSupport.FailureResult failure = flowSupport.handleFailure(
                        RabbitTopologyConfig.Q_RECORDED,
                        eventType,
                        aggregateType,
                        payload,
                        outboxId,
                        message,
                        now,
                        e
                );

                if (failure.dead()) {
                    recordLatencyMetrics(eventType, parsed, MetricsConfig.RESULT_DEAD, now);

                    log.error("[AuthErrorConsumer] DEAD -> DLQ outboxId={}, err={}", outboxId, failure.decision().lastError(), e);
                    // DLQ 격리
                    channel.basicReject(tag, false);
                    return;
                }

                log.warn("[AuthErrorConsumer] RETRY -> outboxId={}, nextRetryCount={}, nextRetryAt={}, err={}",
                        outboxId, failure.decision().nextRetryCount(), failure.decision().nextRetryAt(), failure.decision().lastError());

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

    private void applyLoadTestDelay(Long outboxId, String eventType) {
        long delayMs = delayProperties.getRecordedMs();
        if (delayMs <= 0) {
            return;
        }

        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[AuthErrorConsumer] load-test delay interrupted. outboxId={}, eventType={}", outboxId, eventType);
        }
    }

    private void maybeInjectLoadTestFailure(Long outboxId,
                                            String eventType,
                                            AuthErrorRecordedPayload parsed,
                                            int currentRetry) {
        if (!failureInjectionProperties.enabled() || parsed == null) {
            return;
        }

        String mode = failureInjectionProperties.normalizedMode();
        if (!"retry-once".equals(mode) && !"retry-until-dead".equals(mode)) {
            return;
        }
        if (!selectedForFailure(parsed.requestId(), outboxId, failureInjectionProperties.normalizedPercent())) {
            return;
        }
        if ("retry-once".equals(mode)
                && currentRetry >= failureInjectionProperties.normalizedFailUntilRetryCount()) {
            return;
        }

        throw new RetryableAuthErrorException("load-test consumer failure injected. mode=" + mode
                + ", outboxId=" + outboxId
                + ", eventType=" + eventType
                + ", currentRetry=" + currentRetry);
    }

    private boolean selectedForFailure(String requestId, Long outboxId, int percent) {
        if (percent <= 0) {
            return false;
        }
        if (percent >= 100) {
            return true;
        }
        String key = (requestId == null || requestId.isBlank()) ? String.valueOf(outboxId) : requestId;
        int bucket = Math.floorMod(key.hashCode(), 100);
        return bucket < percent;
    }
}
