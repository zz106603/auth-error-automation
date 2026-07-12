package com.yunhwan.auth.error.infra.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import com.yunhwan.auth.error.infra.messaging.consumer.listener.ConsumerDelayProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
public class RuntimeConfigMetrics {

    private final MeterRegistry meterRegistry;
    private final Environment environment;
    private final int hikariMaxPoolSize;
    private final int consumerConcurrency;
    private final int consumerMaxConcurrency;
    private final int consumerPrefetch;
    private final ConsumerDelayProperties consumerDelayProperties;

    public RuntimeConfigMetrics(
            MeterRegistry meterRegistry,
            Environment environment,
            ConsumerDelayProperties consumerDelayProperties,
            @Value("${spring.datasource.hikari.maximum-pool-size:0}") int hikariMaxPoolSize,
            @Value("${spring.rabbitmq.listener.simple.concurrency:0}") String consumerConcurrencyRaw,
            @Value("${spring.rabbitmq.listener.simple.max-concurrency:0}") String consumerMaxConcurrencyRaw,
            @Value("${spring.rabbitmq.listener.simple.prefetch:0}") int consumerPrefetch
    ) {
        this.meterRegistry = meterRegistry;
        this.environment = environment;
        this.consumerDelayProperties = consumerDelayProperties;
        this.hikariMaxPoolSize = hikariMaxPoolSize;
        this.consumerConcurrency = parseListenerConcurrency(consumerConcurrencyRaw);
        this.consumerMaxConcurrency = parseListenerConcurrency(consumerMaxConcurrencyRaw);
        this.consumerPrefetch = consumerPrefetch;
    }

    @PostConstruct
    void init() {
        Gauge.builder(MetricsConfig.METRIC_RUNTIME_HIKARI_MAX_POOL_SIZE, () -> hikariMaxPoolSize)
                .register(meterRegistry);

        Gauge.builder(MetricsConfig.METRIC_RUNTIME_CONSUMER_CONCURRENCY, () -> consumerConcurrency)
                .register(meterRegistry);

        Gauge.builder(MetricsConfig.METRIC_RUNTIME_CONSUMER_MAX_CONCURRENCY, () -> consumerMaxConcurrency)
                .register(meterRegistry);

        Gauge.builder(MetricsConfig.METRIC_RUNTIME_CONSUMER_PREFETCH, () -> consumerPrefetch)
                .register(meterRegistry);

        Gauge.builder(MetricsConfig.METRIC_RUNTIME_CONSUMER_DELAY_RECORDED_MS, consumerDelayProperties,
                        ConsumerDelayProperties::getRecordedMs)
                .register(meterRegistry);

        String[] profiles = environment.getActiveProfiles();
        if (profiles != null && profiles.length > 0) {
            for (String profile : profiles) {
                Gauge.builder(MetricsConfig.METRIC_RUNTIME_PROFILE_INFO, () -> 1.0)
                        .tag(MetricsConfig.TAG_PROFILE, sanitizeProfile(profile))
                        .register(meterRegistry);
            }
        } else {
            Gauge.builder(MetricsConfig.METRIC_RUNTIME_PROFILE_INFO, () -> 1.0)
                    .tag(MetricsConfig.TAG_PROFILE, "default")
                    .register(meterRegistry);
        }
    }

    private int parseListenerConcurrency(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if (normalized.contains("-")) {
            String[] parts = normalized.split("-", 2);
            return parseIntSafe(parts[0]);
        }
        return parseIntSafe(normalized);
    }

    private int parseIntSafe(String raw) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private String sanitizeProfile(String profile) {
        if (profile == null || profile.isBlank()) {
            return "unknown";
        }
        return profile.trim();
    }
}
