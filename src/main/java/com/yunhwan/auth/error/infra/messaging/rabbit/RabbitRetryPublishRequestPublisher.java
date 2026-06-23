package com.yunhwan.auth.error.infra.messaging.rabbit;

import com.yunhwan.auth.error.domain.consumer.RetryPublishRequest;
import com.yunhwan.auth.error.usecase.consumer.port.RetryPublishRequestPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RabbitRetryPublishRequestPublisher implements RetryPublishRequestPublisher {

    private static final String RETRY_HEADER = "x-retry-count";

    private final ConfirmedRabbitPublisher confirmedRabbitPublisher;
    private final RetryRoutingResolver retryRoutingResolver;

    @Override
    public void publish(RetryPublishRequest request) throws Exception {
        String exchange = retryRoutingResolver.retryExchange(request.getEventType());
        String routingKey = retryRoutingResolver.resolve(request.getEventType(), request.getRetryCount());

        confirmedRabbitPublisher.publish(
                exchange,
                routingKey,
                request.getPayload(),
                "retry-publish-" + request.getId(),
                request.getEventType(),
                msg -> {
                    MessageProperties props = msg.getMessageProperties();
                    props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
                    props.setCorrelationId("retry-publish-" + request.getId());
                    props.setHeader("outboxId", request.getSourceOutboxId());
                    props.setHeader("eventType", request.getEventType());
                    props.setHeader("aggregateType", request.getAggregateType());
                    props.setHeader(RETRY_HEADER, request.getRetryCount());
                    if (request.getLastError() != null) {
                        props.setHeader("x-last-error", request.getLastError());
                    }
                    props.setHeader("x-next-retry-at", request.getNextRetryAt().toString());
                    return msg;
                }
        );
    }
}
