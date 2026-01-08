package com.yunhwan.auth.error.consumer.listener;

import com.yunhwan.auth.error.config.rabbitmq.RabbitTopologyConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class AuthErrorDlqConsumer {

    @RabbitListener(queues = RabbitTopologyConfig.DLQ)
    public void onDlq(String payload,
                      @Header(name = "outboxId", required = false) Long outboxId) {
        log.warn("[AuthErrorDLQ] RECEIVED outboxId={}, payload={}", outboxId, payload);
    }
}