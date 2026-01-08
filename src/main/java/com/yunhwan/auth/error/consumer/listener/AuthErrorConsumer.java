package com.yunhwan.auth.error.consumer.listener;

import com.yunhwan.auth.error.config.rabbitmq.RabbitTopologyConfig;
import com.rabbitmq.client.Channel;
import com.yunhwan.auth.error.consumer.handler.AuthErrorHandler;
import com.yunhwan.auth.error.consumer.persistence.ProcessedMessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class AuthErrorConsumer {

    private static final int MAX_RETRY = 3;
    private static final String RETRY_HEADER = "x-retry-count";

    private final RabbitTemplate rabbitTemplate;
    private final AuthErrorHandler handler;
    private final ProcessedMessageRepository processedMessageRepo;

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

        // 1) outboxId 없으면 메시지 형식 불량 → 운영상 DLQ 격리 권장
        if (outboxId == null) {
            log.warn("[AuthErrorConsumer] missing outboxId -> reject. payload={}", payload);
            channel.basicReject(tag, false);
            return;
        }

        // 2) Idempotency gate (처리 전에 먼저!)
        int inserted = processedMessageRepo.insertIgnore(outboxId);
        if (inserted == 0) {
            log.info("[AuthErrorConsumer] duplicate message -> ack. outboxId={}", outboxId);
            channel.basicAck(tag, false);
            return;
        }

        // 3) handler로 위임 (consumer는 얇게)
        try {
            Map<String, Object> headers = new HashMap<>();
            headers.put("outboxId", outboxId);
            headers.put("eventType", eventType);
            headers.put("aggregateType", aggregateType);

            handler.handle(payload, headers);

            channel.basicAck(tag, false);

        } catch (Exception e) {
            int retry = getRetryCount(message);

            if (retry >= MAX_RETRY) {
                log.error("[AuthErrorConsumer] FAIL -> DLQ outboxId={}, retry={}, err={}",
                        outboxId, retry, e.getMessage(), e);
                channel.basicReject(tag, false); // DLQ
                return;
            }

            int nextRetry = retry + 1;
            log.warn("[AuthErrorConsumer] FAIL -> RETRY outboxId={}, retry={}/{} err={}",
                    outboxId, nextRetry, MAX_RETRY, e.getMessage());

            // Retry Exchange로 다시 발행 (헤더 유지 + retry 증가)
            rabbitTemplate.convertAndSend(
                    RabbitTopologyConfig.RETRY_EXCHANGE,
                    RabbitTopologyConfig.RETRY_ROUTING_KEY_10S,
                    payload,
                    msg -> {
                        MessageProperties p = msg.getMessageProperties();
                        p.setContentType(MessageProperties.CONTENT_TYPE_JSON);

                        // 원래 헤더 복사(가능한 한)
                        p.getHeaders().putAll(message.getMessageProperties().getHeaders());
                        p.setHeader(RETRY_HEADER, nextRetry);
                        return msg;
                    }
            );

            // 원본 메시지는 ACK 처리(중복 재전달 방지)
            channel.basicAck(tag, false);
        }
    }

    private int getRetryCount(Message message) {
        Object v = message.getMessageProperties().getHeaders().get(RETRY_HEADER);
        if (v instanceof Number num) {
            return num.intValue();
        }
        if (v instanceof String s) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException ignore) {
                // It's not a number, fall through to default
            }
        }
        return 0;
    }
}
