package com.yunhwan.auth.error.infra.messaging.consumer.listener;

import com.yunhwan.auth.error.infra.messaging.rabbit.RabbitTopologyConfig;
import com.yunhwan.auth.error.usecase.consumer.port.DlqHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class AuthErrorRecordedDlqConsumer {

    private final ObjectProvider<DlqHandler> dlqObserverProvider;

    @RabbitListener(queues = RabbitTopologyConfig.DLQ_RECORDED)
    public void onDlq(String payload,
                      @Header(name = "outboxId", required = false) Long outboxId) {
        log.warn("[AuthErrorDLQ] RECEIVED outboxId={}, payload={}", outboxId, payload);

        DlqHandler observer = dlqObserverProvider.getIfAvailable();
        if (observer != null) {
            observer.onDlq(outboxId, payload);
        }
    }
}