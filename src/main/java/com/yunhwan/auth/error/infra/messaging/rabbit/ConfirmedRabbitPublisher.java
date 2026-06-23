package com.yunhwan.auth.error.infra.messaging.rabbit;

import com.yunhwan.auth.error.common.exception.NonRetryablePublishException;
import com.yunhwan.auth.error.common.exception.RetryablePublishException;
import com.yunhwan.auth.error.infra.metrics.MetricsConfig;
import io.micrometer.core.instrument.Counter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
@Component
@RequiredArgsConstructor
public class ConfirmedRabbitPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final io.micrometer.core.instrument.MeterRegistry meterRegistry;

    public void publish(
            String exchange,
            String routingKey,
            String payload,
            String correlationId,
            String metricEventType,
            MessagePostProcessor messagePostProcessor
    ) throws Exception {
        CorrelationData cd = new CorrelationData(correlationId);

        rabbitTemplate.convertAndSend(exchange, routingKey, payload, messagePostProcessor, cd);

        try {
            CorrelationData.Confirm confirm = cd.getFuture().get(3, TimeUnit.SECONDS);
            if (!confirm.isAck()) {
                publishCounter(metricEventType, MetricsConfig.RESULT_NACK).increment();
                throw new RetryablePublishException("Broker NACK. reason=" + confirm.getReason(), null);
            }

            ReturnedMessage returned = cd.getReturned();
            if (returned != null) {
                publishCounter(metricEventType, MetricsConfig.RESULT_RETURNED).increment();
                throw new NonRetryablePublishException("Routing failed. replyCode=" + returned.getReplyCode());
            }

            publishCounter(metricEventType, MetricsConfig.RESULT_SUCCESS).increment();
        } catch (TimeoutException e) {
            publishCounter(metricEventType, MetricsConfig.RESULT_TIMEOUT).increment();
            throw new RetryablePublishException("Confirm timeout", e);
        } catch (Exception e) {
            publishCounter(metricEventType, MetricsConfig.RESULT_ERROR).increment();
            throw e;
        }
    }

    private Counter publishCounter(String eventType, String result) {
        return Counter.builder(MetricsConfig.METRIC_PUBLISH)
                .tag(MetricsConfig.TAG_EVENT_TYPE, eventType)
                .tag(MetricsConfig.TAG_RESULT, result)
                .register(meterRegistry);
    }
}
