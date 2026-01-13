package com.yunhwan.auth.error.consumer.listener;

import com.yunhwan.auth.error.config.rabbitmq.RabbitTopologyConfig;
import com.yunhwan.auth.error.consumer.observer.DlqObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class AuthErrorDlqConsumer {

    private final ObjectProvider<DlqObserver> dlqObserverProvider;

    @RabbitListener(queues = RabbitTopologyConfig.DLQ)
    public void onDlq(String payload,
                      @Header(name = "outboxId", required = false) Long outboxId) {
        log.warn("[AuthErrorDLQ] RECEIVED outboxId={}, payload={}", outboxId, payload);

        DlqObserver observer = dlqObserverProvider.getIfAvailable();
        if (observer != null) {
            observer.onDlq(outboxId, payload);
        }
    }
}