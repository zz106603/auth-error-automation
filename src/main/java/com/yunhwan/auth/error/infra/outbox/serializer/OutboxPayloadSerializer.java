package com.yunhwan.auth.error.infra.outbox.serializer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yunhwan.auth.error.domain.outbox.policy.PayloadSerializer;
import com.yunhwan.auth.error.infra.metrics.MetricsConfig;
import com.yunhwan.auth.error.infra.metrics.RecordedConsumerMetricsContext;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
public class OutboxPayloadSerializer implements PayloadSerializer {

    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    @Override
    public String serialize(Object payload) {
        RecordedConsumerMetricsContext.MetricContext context =
                RecordedConsumerMetricsContext.current().orElse(null);
        if (context == null) {
            return serializeInternal(payload);
        }

        long startedAt = System.nanoTime();
        try {
            return serializeInternal(payload);
        } finally {
            Timer.builder(MetricsConfig.METRIC_OUTBOX_PAYLOAD_SERIALIZE)
                    .tag(MetricsConfig.TAG_EVENT_TYPE, context.eventType())
                    .register(meterRegistry)
                    .record(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS);
        }
    }

    private String serializeInternal(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize outbox payload", e);
        }
    }
}
