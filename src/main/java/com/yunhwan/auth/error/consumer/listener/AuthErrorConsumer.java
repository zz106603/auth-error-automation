package com.yunhwan.auth.error.consumer.listener;

import com.rabbitmq.client.Channel;
import com.yunhwan.auth.error.config.rabbitmq.RabbitTopologyConfig;
import com.yunhwan.auth.error.consumer.decision.ConsumerDecisionMaker;
import com.yunhwan.auth.error.consumer.handler.AuthErrorHandler;
import com.yunhwan.auth.error.consumer.persistence.ProcessedMessageRepository;
import com.yunhwan.auth.error.consumer.util.HeaderUtils;
import com.yunhwan.auth.error.domain.consumer.ProcessedStatus;
import com.yunhwan.auth.error.outbox.support.OutboxDecision;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class AuthErrorConsumer {

    private static final String RETRY_HEADER = "x-retry-count";
    private static final Duration LEASE_DURATION = Duration.ofSeconds(60);

    private final RabbitTemplate rabbitTemplate;
    private final AuthErrorHandler handler;
    private final ProcessedMessageRepository processedMessageRepo;
    private final ConsumerDecisionMaker decisionMaker;

    @RabbitListener(queues = RabbitTopologyConfig.QUEUE)
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

        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime leaseUntil = now.plusSeconds(LEASE_DURATION.getSeconds());

        // 2) 선점
        int claimed = processedMessageRepo.claimProcessing(
                outboxId, now, leaseUntil,
                ProcessedStatus.PROCESSING.name()
        );
        if (claimed == 0) {
            // 누군가 처리 중(lease 유효) 또는 이미 DONE
            channel.basicAck(tag, false);
            return;
        }

        int currentRetry = HeaderUtils.getRetryCount(message.getMessageProperties().getHeaders());

        try {
            // 3) handler 위임
            handler.handle(payload, buildHeaders(outboxId, eventType, aggregateType, currentRetry));

            // 4) 성공 확정
            processedMessageRepo.markDone(
                    outboxId, OffsetDateTime.now(),
                    ProcessedStatus.DONE.name(),
                    ProcessedStatus.PROCESSING.name()
            );

            channel.basicAck(tag, false);
        } catch (Exception e) {
            OutboxDecision decision = decisionMaker.decide(now, currentRetry, e);

            if (decision.isDead()) {
                // ✅ 여기서 DB 상태도 정리해두는 걸 추천(최소 DONE 처리라도)
                processedMessageRepo.markDone(
                        outboxId, OffsetDateTime.now(),
                        ProcessedStatus.DONE.name(),
                        ProcessedStatus.PROCESSING.name()
                );

                log.error("[AuthErrorConsumer] DEAD -> DLQ outboxId={}, nextRetry={}, err={}",
                        outboxId, decision.nextRetryCount(), decision.lastError(), e);

                channel.basicReject(tag, false);
                return;
            }

            // RETRY
            log.warn("[AuthErrorConsumer] RETRY outboxId={}, nextRetry={}, at={}, err={}",
                    outboxId, decision.nextRetryCount(), decision.nextRetryAt(), decision.lastError(), e);

            // 재발행(헤더 유지 + retry 갱신)
            republishToRetryExchange(payload, message, decision);

            // lease 만료/해제 (다음 메시지가 선점 가능하게)
            processedMessageRepo.releaseLeaseForRetry(
                    outboxId,
                    now,
                    ProcessedStatus.PROCESSING.name()
            );

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

    private void republishToRetryExchange(String payload, Message original, OutboxDecision decision) {
        rabbitTemplate.convertAndSend(
                RabbitTopologyConfig.RETRY_EXCHANGE,
                // ✅ 네 topology가 delaySeconds 고정이면 그대로,
                //    나중에 nextRetryAt 기반으로 routing 선택/TTL 헤더로 확장 가능
                RabbitTopologyConfig.RETRY_ROUTING_KEY_10S,
                payload,
                msg -> {
                    MessageProperties p = msg.getMessageProperties();
                    p.setContentType(MessageProperties.CONTENT_TYPE_JSON);

                    // 기존 헤더 유지
                    p.getHeaders().putAll(original.getMessageProperties().getHeaders());

                    // retry 카운트 업데이트 (policy 기반)
                    p.setHeader(RETRY_HEADER, decision.nextRetryCount());

                    // lastError/nextRetryAt 기록
                     p.setHeader("x-last-error", decision.lastError());
                     p.setHeader("x-next-retry-at", decision.nextRetryAt().toString());

                    return msg;
                }
        );
    }
}
