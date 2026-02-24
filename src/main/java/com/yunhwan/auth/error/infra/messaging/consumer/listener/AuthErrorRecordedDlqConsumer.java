package com.yunhwan.auth.error.infra.messaging.consumer.listener;

import com.yunhwan.auth.error.infra.messaging.rabbit.RabbitTopologyConfig;
import com.yunhwan.auth.error.infra.metrics.MetricsConfig;
import com.yunhwan.auth.error.usecase.consumer.port.DlqHandler;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
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
    private final MeterRegistry meterRegistry;

    private Counter dlqArrivedCounter;

    @PostConstruct
    void initMetrics() {
        this.dlqArrivedCounter = Counter.builder(MetricsConfig.METRIC_DLQ)
                .tag(MetricsConfig.TAG_EVENT_TYPE, RabbitTopologyConfig.RK_RECORDED)
                .tag(MetricsConfig.TAG_QUEUE, RabbitTopologyConfig.DLQ_RECORDED)
                .tag(MetricsConfig.TAG_REASON, MetricsConfig.REASON_DLQ_ARRIVED)
                .register(meterRegistry);
    }

    @RabbitListener(queues = RabbitTopologyConfig.DLQ_RECORDED)
    public void onDlq(String payload,
                      @Header(name = "outboxId", required = false) Long outboxId) {
        log.warn("[AuthErrorDLQ] RECEIVED outboxId={}, payload={}", outboxId, payload);
        // 실제 DLQ 적재 확인용 (사전 reject와 분리)
        dlqArrivedCounter.increment();

        DlqHandler observer = dlqObserverProvider.getIfAvailable();
        if (observer != null) {
            observer.onDlq(outboxId, payload);
        }
    }
}
