package com.yunhwan.auth.error.infra.messaging.consumer.listener;

import com.yunhwan.auth.error.common.exception.NonRetryableAuthErrorException;
import com.yunhwan.auth.error.infra.metrics.MetricTags;
import com.yunhwan.auth.error.infra.metrics.MetricsConfig;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public final class ConsumerListenerSupport {

    public static final String RETRY_HEADER = "x-retry-count";

    private ConsumerListenerSupport() {
    }

    public static Map<String, Object> buildHeaders(Long outboxId, String eventType, String aggregateType, int retry) {
        Map<String, Object> headers = new HashMap<>();
        headers.put("outboxId", outboxId);
        headers.put("eventType", eventType);
        headers.put("aggregateType", aggregateType);
        headers.put(RETRY_HEADER, retry);
        return headers;
    }

    public static Counter consumeCounter(MeterRegistry meterRegistry, String queue, String eventType, String result) {
        return Counter.builder(MetricsConfig.METRIC_CONSUME)
                .tag(MetricsConfig.TAG_EVENT_TYPE, eventTypeOrUnknown(eventType))
                .tag(MetricsConfig.TAG_QUEUE, queue)
                .tag(MetricsConfig.TAG_RESULT, result)
                .register(meterRegistry);
    }

    public static Counter retryEnqueueCounter(
            MeterRegistry meterRegistry,
            String queue,
            String eventType,
            int nextRetryCount,
            String reason
    ) {
        return Counter.builder(MetricsConfig.METRIC_RETRY_ENQUEUE)
                .tag(MetricsConfig.TAG_EVENT_TYPE, eventTypeOrUnknown(eventType))
                .tag(MetricsConfig.TAG_QUEUE, queue)
                .tag(MetricsConfig.TAG_RETRY_BUCKET, MetricTags.retryBucket(nextRetryCount))
                .tag(MetricsConfig.TAG_REASON, reason)
                .register(meterRegistry);
    }

    public static Counter dlqCounter(MeterRegistry meterRegistry, String queue, String eventType, String reason) {
        return Counter.builder(MetricsConfig.METRIC_DLQ)
                .tag(MetricsConfig.TAG_EVENT_TYPE, eventTypeOrUnknown(eventType))
                .tag(MetricsConfig.TAG_QUEUE, queue)
                .tag(MetricsConfig.TAG_REASON, reason)
                .register(meterRegistry);
    }

    public static String deadReason(Exception e) {
        if (e instanceof NonRetryableAuthErrorException) {
            return MetricsConfig.REASON_NON_RETRYABLE;
        }
        return MetricsConfig.REASON_MAX_RETRIES;
    }

    public static String eventTypeOrUnknown(String eventType) {
        return eventType == null ? "unknown" : eventType;
    }

    public static int payloadSizeBytes(String payload) {
        return payload == null ? 0 : payload.getBytes(StandardCharsets.UTF_8).length;
    }
}
