package com.yunhwan.auth.error.outbox.publisher;

import com.yunhwan.auth.error.domain.outbox.OutboxMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RabbitOutboxPublisher implements OutboxPublisher {

    private final RabbitTemplate rabbitTemplate;

    @Override
    public void publish(OutboxMessage message) {
        // TODO: 실제 exchange/routingKey 전략은 네가 쓰는 값으로 맞춰라
        String exchange = "auth.error.exchange";
        String routingKey = "auth.error.v1";

        rabbitTemplate.convertAndSend(exchange, routingKey, message.getPayload());
    }
}