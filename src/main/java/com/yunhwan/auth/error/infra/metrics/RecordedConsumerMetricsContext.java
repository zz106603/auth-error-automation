package com.yunhwan.auth.error.infra.metrics;

import java.util.Optional;

public final class RecordedConsumerMetricsContext {

    private static final ThreadLocal<MetricContext> CURRENT = new ThreadLocal<>();

    private RecordedConsumerMetricsContext() {}

    public static Scope open(String eventType, String queue) {
        MetricContext previous = CURRENT.get();
        CURRENT.set(new MetricContext(eventType, queue));
        return () -> {
            if (previous == null) {
                CURRENT.remove();
                return;
            }
            CURRENT.set(previous);
        };
    }

    public static Optional<MetricContext> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public record MetricContext(String eventType, String queue) {}

    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
