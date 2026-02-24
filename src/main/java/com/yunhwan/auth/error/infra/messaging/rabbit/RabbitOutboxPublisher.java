package com.yunhwan.auth.error.infra.messaging.rabbit;

import com.yunhwan.auth.error.common.exception.NonRetryablePublishException;
import com.yunhwan.auth.error.common.exception.RetryablePublishException;
import com.yunhwan.auth.error.domain.outbox.OutboxMessage;
import com.yunhwan.auth.error.infra.metrics.MetricsConfig;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import com.yunhwan.auth.error.usecase.outbox.port.OutboxPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * RabbitMQ로 Outbox 메시지를 발행하는 구현체.
 * <p>
 * 단순 전송(fire-and-forget)이 아니라,
 * 1. 브로커 수신 확인 (Publisher Confirms: Ack/Nack)
 * 2. 라우팅 실패 확인 (Returns Callback)
 * 을 통해 신뢰성 있는 발행을 보장한다.
 */
@Slf4j
@Component
public class RabbitOutboxPublisher implements OutboxPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final MeterRegistry meterRegistry;
    private final AtomicLong lastPublishSuccessEpochMs = new AtomicLong(0);

    public RabbitOutboxPublisher(RabbitTemplate rabbitTemplate, MeterRegistry meterRegistry) {
        this.rabbitTemplate = rabbitTemplate;
        this.meterRegistry = meterRegistry;
        // publish 정지 감지용 (silent collapse)
        Gauge.builder(MetricsConfig.METRIC_PUBLISH_LAST_SUCCESS_EPOCH_MS, lastPublishSuccessEpochMs, AtomicLong::get)
                .register(meterRegistry);
    }

    @Override
    public void publish(OutboxMessage message) throws Exception {
        log.info("[RabbitOutboxPublisher] Publishing message. id={}, type={}",
                message.getId(), message.getEventType());

        // CorrelationData 생성: 비동기 응답(Ack/Nack) 및 Return 추적
        CorrelationData cd = new CorrelationData("outbox-" + message.getId());
        CompletableFuture<CorrelationData.Confirm> confirmFuture = cd.getFuture();

        String routingKey = message.getEventType(); // e.g. RK_RECORDED / RK_ANALYSIS_REQUESTED

        rabbitTemplate.convertAndSend(
                RabbitTopologyConfig.EXCHANGE,
                routingKey,
                message.getPayload(),
                msg -> {
                    MessageProperties props = msg.getMessageProperties();
                    props.setContentType(MessageProperties.CONTENT_TYPE_JSON);

                    // 추적용 ID 설정
                    props.setCorrelationId(cd.getId());
                    props.setHeader("outboxId", message.getId());
                    props.setHeader("eventType", message.getEventType());
                    props.setHeader("aggregateType", message.getAggregateType());
                    return msg;
                },
                cd
        );

        try {
            // 1) Broker Confirm (Ack/Nack) 대기 (최대 3초)
            CorrelationData.Confirm confirm = confirmFuture.get(3, TimeUnit.SECONDS);

            if (!confirm.isAck()) {
                log.warn("[RabbitOutboxPublisher] Broker NACK. id={}, reason={}",
                        message.getId(), confirm.getReason());
                // publish_rate 분모(성공/실패 구분)
                publishCounter(message.getEventType(), MetricsConfig.RESULT_NACK).increment();
                throw new RetryablePublishException("Broker NACK. reason=" + confirm.getReason(), null);
            }

            // 2) Routing 실패(Return) 검사
            // RabbitMQ 프로토콜상 Return이 Ack보다 먼저 도착하므로,
            // Ack를 받은 시점에 ReturnedMessage가 존재한다면 라우팅 실패로 간주해야 함.
            ReturnedMessage returned = cd.getReturned();
            if (returned != null) {
                log.warn("[RabbitOutboxPublisher] Message RETURNED (Routing Failed). id={}, replyCode={}, replyText={}",
                        message.getId(), returned.getReplyCode(), returned.getReplyText());
                // non-retryable 분리 지표
                publishCounter(message.getEventType(), MetricsConfig.RESULT_RETURNED).increment();
                throw new NonRetryablePublishException("Routing failed. replyCode=" + returned.getReplyCode());
            }

            // 성공 로그
            log.info("[RabbitOutboxPublisher] Publish SUCCESS. id={}", message.getId());
            // publish_rate 기준선
            publishCounter(message.getEventType(), MetricsConfig.RESULT_SUCCESS).increment();
            lastPublishSuccessEpochMs.set(System.currentTimeMillis());

        } catch (TimeoutException e) {
            log.error("[RabbitOutboxPublisher] Confirm timeout. id={}", message.getId());
            // broker 지연 탐지
            publishCounter(message.getEventType(), MetricsConfig.RESULT_TIMEOUT).increment();
            throw new RetryablePublishException("Confirm timeout", e);
        } catch (Exception e) {
            // 그 외 예외 (InterruptedException 등)
            log.error("[RabbitOutboxPublisher] Publish ERROR. id={}, error={}", message.getId(), e.getMessage(), e);
            // 일반 실패 집계
            publishCounter(message.getEventType(), MetricsConfig.RESULT_ERROR).increment();
            throw e;
        }
    }

    private Counter publishCounter(String eventType, String result) {
        // event_type+result만 사용
        return Counter.builder(MetricsConfig.METRIC_PUBLISH)
                .tag(MetricsConfig.TAG_EVENT_TYPE, eventType)
                .tag(MetricsConfig.TAG_RESULT, result)
                .register(meterRegistry);
    }
}
