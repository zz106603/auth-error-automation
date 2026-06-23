package com.yunhwan.auth.error.infra.messaging.rabbit;

import com.yunhwan.auth.error.domain.outbox.OutboxMessage;
import com.yunhwan.auth.error.infra.metrics.MetricsConfig;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import com.yunhwan.auth.error.usecase.outbox.port.OutboxPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

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

    private final ConfirmedRabbitPublisher confirmedRabbitPublisher;
    private final MeterRegistry meterRegistry;
    private final AtomicLong lastPublishSuccessEpochMs = new AtomicLong(0);
    private final Timer outboxPublishAdapterTimer;

    public RabbitOutboxPublisher(ConfirmedRabbitPublisher confirmedRabbitPublisher, MeterRegistry meterRegistry) {
        this.confirmedRabbitPublisher = confirmedRabbitPublisher;
        this.meterRegistry = meterRegistry;
        // publish 정지 감지용 (silent collapse)
        Gauge.builder(MetricsConfig.METRIC_PUBLISH_LAST_SUCCESS_EPOCH_MS, lastPublishSuccessEpochMs, AtomicLong::get)
                .register(meterRegistry);
        this.outboxPublishAdapterTimer = Timer.builder(MetricsConfig.METRIC_OUTBOX_PUBLISH_ADAPTER)
                .register(meterRegistry);
    }

    @Override
    public void publish(OutboxMessage message) throws Exception {
        Timer.Sample sample = Timer.start(meterRegistry);
        log.info("[RabbitOutboxPublisher] Publishing message. id={}, type={}",
                message.getId(), message.getEventType());

        String routingKey = message.getEventType(); // e.g. RK_RECORDED / RK_ANALYSIS_REQUESTED

        try {
            confirmedRabbitPublisher.publish(
                    RabbitTopologyConfig.EXCHANGE,
                    routingKey,
                    message.getPayload(),
                    "outbox-" + message.getId(),
                    message.getEventType(),
                    msg -> {
                        MessageProperties props = msg.getMessageProperties();
                        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);

                        // 추적용 ID 설정
                        props.setCorrelationId("outbox-" + message.getId());
                        props.setHeader("outboxId", message.getId());
                        props.setHeader("eventType", message.getEventType());
                        props.setHeader("aggregateType", message.getAggregateType());
                        return msg;
                    }
            );

            // 성공 로그
            log.info("[RabbitOutboxPublisher] Publish SUCCESS. id={}", message.getId());
            lastPublishSuccessEpochMs.set(System.currentTimeMillis());
        } catch (Exception e) {
            log.error("[RabbitOutboxPublisher] Publish ERROR. id={}, error={}", message.getId(), e.getMessage(), e);
            throw e;
        } finally {
            sample.stop(outboxPublishAdapterTimer);
        }
    }
}
