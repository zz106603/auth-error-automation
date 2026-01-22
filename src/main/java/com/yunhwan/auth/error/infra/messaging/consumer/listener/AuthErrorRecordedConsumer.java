package com.yunhwan.auth.error.infra.messaging.consumer.listener;

import com.rabbitmq.client.Channel;
import com.yunhwan.auth.error.domain.consumer.ProcessedStatus;
import com.yunhwan.auth.error.domain.outbox.decision.OutboxDecision;
import com.yunhwan.auth.error.infra.messaging.rabbit.RabbitTopologyConfig;
import com.yunhwan.auth.error.infra.messaging.rabbit.RetryRoutingResolver;
import com.yunhwan.auth.error.infra.support.HeaderUtils;
import com.yunhwan.auth.error.usecase.consumer.ConsumerDecisionMaker;
import com.yunhwan.auth.error.usecase.consumer.handler.AuthErrorHandler;
import com.yunhwan.auth.error.usecase.consumer.port.ProcessedMessageStore;
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
public class AuthErrorRecordedConsumer {

    private static final String RETRY_HEADER = "x-retry-count";
    private static final Duration LEASE_DURATION = Duration.ofSeconds(60);

    private final RabbitTemplate rabbitTemplate;
    private final AuthErrorHandler handler;
    private final ProcessedMessageStore processedMessageStore;
    private final ConsumerDecisionMaker decisionMaker;
    private final RetryRoutingResolver retryRoutingResolver;

    public AuthErrorRecordedConsumer(
            RabbitTemplate rabbitTemplate,
            @Qualifier("authErrorRecordedHandler") AuthErrorHandler handler,
            ProcessedMessageStore processedMessageStore,
            ConsumerDecisionMaker decisionMaker,
            RetryRoutingResolver retryRoutingResolver
    ) {
        this.rabbitTemplate = rabbitTemplate;
        this.handler = handler;
        this.processedMessageStore = processedMessageStore;
        this.decisionMaker = decisionMaker;
        this.retryRoutingResolver = retryRoutingResolver;
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
            log.warn("[AuthErrorConsumer] missing outboxId -> reject(DLQ). payload={}", payload);
            channel.basicReject(tag, false);
            return;
        }

        if (eventType == null || aggregateType == null) {
            log.warn("[AuthErrorConsumer] missing headers -> reject(DLQ). outboxId={}, eventType={}, aggregateType={}",
                    outboxId, eventType, aggregateType);
            channel.basicReject(tag, false);
            return;
        }

        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime leaseUntil = now.plusSeconds(LEASE_DURATION.getSeconds());

        // 2) 선점
        processedMessageStore.ensureRowExists(outboxId, now);
        int claimed = processedMessageStore.claimProcessingUpdate(outboxId, now, leaseUntil);
        if (claimed == 0) {
            ProcessedStatus status = processedMessageStore.findStatusByOutboxId(outboxId).orElse(null);
            log.warn("[AuthErrorConsumer] claim failed -> ack(drop). outboxId={}, status={}", outboxId, status);
            channel.basicAck(tag, false);
            return;
        }

        try {
            int currentRetry = HeaderUtils.getRetryCount(message.getMessageProperties().getHeaders());
            // 3) handler 위임
            handler.handle(payload, buildHeaders(outboxId, eventType, aggregateType, currentRetry));

            // 4) 성공 확정
            processedMessageStore.markDone(outboxId, OffsetDateTime.now());

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

                log.error("[AuthErrorConsumer] DEAD -> DLQ outboxId={}, err={}", outboxId, decision.lastError(), e);
                // DLQ 격리
                channel.basicReject(tag, false);
                return;
            }

            // 실패 기록: PROCESSING -> RETRY_WAIT (DB에 retry 정책을 기록)
            processedMessageStore.markRetryWait(
                    outboxId,
                    now,
                    decision.nextRetryAt(),
                    decision.nextRetryCount(),
                    decision.lastError()
            );

            log.warn("[AuthErrorConsumer] RETRY -> outboxId={}, nextRetryCount={}, nextRetryAt={}, err={}",
                    outboxId, decision.nextRetryCount(), decision.nextRetryAt(), decision.lastError());

            // 재발행(헤더 유지 + retry 갱신)
            republishToRetryExchange(payload, eventType, message, decision);

            // 원본 메시지 ACK
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

    private void republishToRetryExchange(String payload, String eventType, Message original, OutboxDecision decision) {
        String retryExchange = retryRoutingResolver.retryExchange(eventType);
        String routingKey = retryRoutingResolver.resolve(eventType, decision.nextRetryCount());

        rabbitTemplate.convertAndSend(
                retryExchange,
                routingKey,
                payload,
                msg -> {
                    MessageProperties p = msg.getMessageProperties();
                    p.setContentType(MessageProperties.CONTENT_TYPE_JSON);

                    // 기존 헤더 유지
                    p.getHeaders().putAll(original.getMessageProperties().getHeaders());

                    // retry 카운트 업데이트 (policy 기반)
                    p.setHeader(RETRY_HEADER, decision.nextRetryCount());

                    // lastError/nextRetryAt 기록
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
}
