package com.yunhwan.auth.error.testsupport.messaging;

import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
public class DuplicateDeliveryInjector {

    private final RabbitTemplate rabbitTemplate;

    public DuplicateDeliveryInjector(RabbitTemplate rabbitTemplate){
        this.rabbitTemplate = rabbitTemplate;
    }

    /**
     * AuthErrorConsumer가 기대하는 헤더(outboxId/eventType/aggregateType)를 그대로 실어서
     * main routingKey로 메시지를 한 번 더 넣는다.
     *
     * exchange/routingKey는 네 topology에 맞게 바꿔야 함.
     */
    public void sendDuplicate(String exchange, String routingKey,
                              String payload,
                              long outboxId,
                              String eventType,
                              String aggregateType) {

        MessagePostProcessor mpp = msg -> {
            var props = msg.getMessageProperties();
            props.setHeader("outboxId", outboxId);
            props.setHeader("eventType", eventType);
            props.setHeader("aggregateType", aggregateType);
            return msg;
        };

        rabbitTemplate.convertAndSend(exchange, routingKey, payload, mpp);
    }
}
