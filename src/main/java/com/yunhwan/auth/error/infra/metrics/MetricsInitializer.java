package com.yunhwan.auth.error.infra.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

@Component
public class MetricsInitializer {

    private final MeterRegistry meterRegistry;

    public MetricsInitializer(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    void init() {

        // Ingest baseline (ensure series exists)
        Counter.builder("auth_error.ingest")
                .tag("result", "success")
                .tag("api", "/api/auth-errors")
                .register(meterRegistry);

        // Retry enqueue baseline (Recorded path)
        Counter.builder("auth_error.retry.enqueue")
                .tag("event_type", "auth.error.recorded.v1")
                .tag("queue", "auth.error.recorded.q")
                .tag("retry_bucket", "1")
                .tag("reason", "retryable")
                .register(meterRegistry);

        // DLQ baseline (Recorded path)
        Counter.builder("auth_error.dlq")
                .tag("event_type", "auth.error.recorded.v1")
                .tag("queue", "auth.error.recorded.q")
                .tag("reason", "dlq_arrived")
                .register(meterRegistry);
    }
}
