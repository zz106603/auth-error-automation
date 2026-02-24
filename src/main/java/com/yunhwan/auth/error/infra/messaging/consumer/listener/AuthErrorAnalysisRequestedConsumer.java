package com.yunhwan.auth.error.infra.messaging.consumer.listener;

import com.rabbitmq.client.Channel;
import com.yunhwan.auth.error.domain.consumer.ProcessedStatus;
import com.yunhwan.auth.error.domain.outbox.decision.OutboxDecision;
import com.yunhwan.auth.error.common.exception.NonRetryableAuthErrorException;
import com.yunhwan.auth.error.infra.metrics.MetricTags;
import com.yunhwan.auth.error.infra.metrics.MetricsConfig;
import com.yunhwan.auth.error.infra.messaging.rabbit.RabbitTopologyConfig;
import com.yunhwan.auth.error.infra.messaging.rabbit.RetryRoutingResolver;
import com.yunhwan.auth.error.infra.support.HeaderUtils;
import com.yunhwan.auth.error.usecase.consumer.ConsumerDecisionMaker;
import com.yunhwan.auth.error.usecase.consumer.handler.AuthErrorHandler;
import com.yunhwan.auth.error.usecase.consumer.port.ProcessedMessageStore;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
public class AuthErrorAnalysisRequestedConsumer {

    private static final String RETRY_HEADER = "x-retry-count";
    private static final Duration LEASE_DURATION = Duration.ofSeconds(60);

    private final RabbitTemplate rabbitTemplate;
    private final AuthErrorHandler handler;
    private final ProcessedMessageStore processedMessageStore;
    private final ConsumerDecisionMaker decisionMaker;
    private final RetryRoutingResolver retryRoutingResolver;
    private final MeterRegistry meterRegistry;

    public AuthErrorAnalysisRequestedConsumer(
            RabbitTemplate rabbitTemplate,
            @Qualifier("authErrorAnalysisRequestedHandler") AuthErrorHandler handler,
            ProcessedMessageStore processedMessageStore,
            ConsumerDecisionMaker decisionMaker,
            RetryRoutingResolver retryRoutingResolver,
            MeterRegistry meterRegistry
    ) {
        this.rabbitTemplate = rabbitTemplate;
        this.handler = handler;
        this.processedMessageStore = processedMessageStore;
        this.decisionMaker = decisionMaker;
        this.retryRoutingResolver = retryRoutingResolver;
        this.meterRegistry = meterRegistry;
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
            log.warn("[AnalysisConsumer] missing outboxId -> reject(DLQ). payload={}", payload);
            // 사유 고정값으로 집계
            dlqCounter(eventTypeOrUnknown(eventType), MetricsConfig.REASON_MISSING_OUTBOX_ID).increment();
            consumeCounter(eventTypeOrUnknown(eventType), MetricsConfig.RESULT_REJECT).increment();
            channel.basicReject(tag, false);
            return;
        }

        if (eventType == null || aggregateType == null) {
            log.warn("[AnalysisConsumer] missing headers -> reject(DLQ). outboxId={}, eventType={}, aggregateType={}",
                    outboxId, eventType, aggregateType);
            // 헤더 계약 위반
            dlqCounter(eventTypeOrUnknown(eventType), MetricsConfig.REASON_MISSING_HEADERS).increment();
            consumeCounter(eventTypeOrUnknown(eventType), MetricsConfig.RESULT_REJECT).increment();
            channel.basicReject(tag, false);
            return;
        }

        OffsetDateTime now = OffsetDateTime.now();
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

            processedMessageStore.markDone(outboxId, OffsetDateTime.now());
            // consume_rate 기준선 (analysis)
            consumeCounter(eventType, MetricsConfig.RESULT_SUCCESS).increment();
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
                consumeCounter(eventType, MetricsConfig.RESULT_DEAD).increment();
                dlqCounter(eventType, deadReason(e)).increment();
                channel.basicReject(tag, false);
                return;
            }

            processedMessageStore.markRetryWait(
                    outboxId,
                    now,
                    decision.nextRetryAt(),
                    decision.nextRetryCount(),
                    decision.lastError()
            );

            log.warn("[AnalysisConsumer] RETRY -> outboxId={}, nextRetryCount={}, nextRetryAt={}, err={}",
                    outboxId, decision.nextRetryCount(), decision.nextRetryAt(), decision.lastError());

            republishToRetryExchange(payload, message, decision, eventType);
            // retry_enqueue_rate 분자 (analysis)
            consumeCounter(eventType, MetricsConfig.RESULT_RETRY).increment();
            retryEnqueueCounter(eventType, decision.nextRetryCount(), MetricsConfig.REASON_RETRYABLE).increment();

            channel.basicAck(tag, false);
        }
    }

    private Map<String, Object> buildHeaders(Long outboxId, String eventType, String aggregateType, int retry) {
        Map<String, Object> headers = new HashMap<>();
        headers.put("outboxId", outboxId);
        headers.put("eventType", eventType);
        headers.put("aggregateType", aggregateType);
        headers.put(RETRY_HEADER, retry);
        return headers;
    }

    private void republishToRetryExchange(String payload, Message original, OutboxDecision decision, String eventType) {
        String retryExchange = retryRoutingResolver.retryExchange(eventType);
        String routingKey = retryRoutingResolver.resolve(eventType, decision.nextRetryCount());

        rabbitTemplate.convertAndSend(
                retryExchange,
                routingKey,
                payload,
                msg -> {
                    MessageProperties p = msg.getMessageProperties();
                    p.setContentType(MessageProperties.CONTENT_TYPE_JSON);

                    p.getHeaders().putAll(original.getMessageProperties().getHeaders());
                    p.setHeader(RETRY_HEADER, decision.nextRetryCount());

                    if (decision.lastError() != null) {
                        p.setHeader("x-last-error", decision.lastError());
                    }
                    if (decision.nextRetryAt() != null) {
                        p.setHeader("x-next-retry-at", decision.nextRetryAt().toString());
                    }

                    return msg;
                }
        );
    }

    private Counter consumeCounter(String eventType, String result) {
        // consume_rate 집계용
        return Counter.builder(MetricsConfig.METRIC_CONSUME)
                .tag(MetricsConfig.TAG_EVENT_TYPE, eventType)
                .tag(MetricsConfig.TAG_QUEUE, RabbitTopologyConfig.Q_ANALYSIS)
                .tag(MetricsConfig.TAG_RESULT, result)
                .register(meterRegistry);
    }

    private Counter retryEnqueueCounter(String eventType, int nextRetryCount, String reason) {
        // retry 횟수는 1/2/3+로만 집계
        return Counter.builder(MetricsConfig.METRIC_RETRY_ENQUEUE)
                .tag(MetricsConfig.TAG_EVENT_TYPE, eventType)
                .tag(MetricsConfig.TAG_QUEUE, RabbitTopologyConfig.Q_ANALYSIS)
                .tag(MetricsConfig.TAG_RETRY_BUCKET, MetricTags.retryBucket(nextRetryCount))
                .tag(MetricsConfig.TAG_REASON, reason)
                .register(meterRegistry);
    }

    private Counter dlqCounter(String eventType, String reason) {
        // 사유는 고정값만 사용
        return Counter.builder(MetricsConfig.METRIC_DLQ)
                .tag(MetricsConfig.TAG_EVENT_TYPE, eventType)
                .tag(MetricsConfig.TAG_QUEUE, RabbitTopologyConfig.Q_ANALYSIS)
                .tag(MetricsConfig.TAG_REASON, reason)
                .register(meterRegistry);
    }

    private String deadReason(Exception e) {
        if (e instanceof NonRetryableAuthErrorException) {
            return MetricsConfig.REASON_NON_RETRYABLE;
        }
        return MetricsConfig.REASON_MAX_RETRIES;
    }

    private String eventTypeOrUnknown(String eventType) {
        return eventType == null ? "unknown" : eventType;
    }
}
