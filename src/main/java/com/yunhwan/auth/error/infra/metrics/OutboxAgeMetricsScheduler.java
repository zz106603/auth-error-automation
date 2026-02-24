package com.yunhwan.auth.error.infra.metrics;

import com.yunhwan.auth.error.usecase.outbox.port.OutboxMessageStore;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.DistributionSummary;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.concurrent.atomic.AtomicLong;

import static com.yunhwan.auth.error.infra.metrics.MetricsConfig.*;

@Slf4j
@Component
@Profile("!test")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "metrics.outbox-age.enabled", havingValue = "true", matchIfMissing = false)
public class OutboxAgeMetricsScheduler implements SchedulingConfigurer {

    private final OutboxMessageStore outboxMessageStore;
    private final MeterRegistry meterRegistry;
    private final TaskScheduler outboxTaskScheduler;
    private final Clock clock;

    // outbox_age_p95/p99/slope 즉시 조회용
    private final AtomicLong p95Ms = new AtomicLong(0);
    private final AtomicLong p99Ms = new AtomicLong(0);
    private final AtomicLong slopeMsPer10s = new AtomicLong(0);
    private volatile long lastP95Ms = 0;
    // p95/p99 histogram 제공(Actuator percentile)
    private DistributionSummary outboxAgeSummary;

    @PostConstruct
    void init() {
        // Register gauges for direct p95/p99/slope access
        Gauge.builder(METRIC_OUTBOX_AGE_P95, p95Ms, AtomicLong::get).register(meterRegistry);
        Gauge.builder(METRIC_OUTBOX_AGE_P99, p99Ms, AtomicLong::get).register(meterRegistry);
        Gauge.builder(METRIC_OUTBOX_AGE_SLOPE, slopeMsPer10s, AtomicLong::get).register(meterRegistry);

        outboxAgeSummary = DistributionSummary.builder(METRIC_OUTBOX_AGE)
                .publishPercentiles(0.95, 0.99)
                .publishPercentileHistogram()
                .register(meterRegistry);
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar taskRegistrar) {
        taskRegistrar.setScheduler(outboxTaskScheduler);
        // 체크리스트 slope 계산 윈도우(10초)
        taskRegistrar.addFixedDelayTask(this::tick, 10_000L);
    }

    void tick() {
        try {
            OffsetDateTime now = OffsetDateTime.now(clock);
            // per-message 조회 금지, 집계 쿼리 1회만 실행
            OutboxAgeStats stats = outboxMessageStore.loadOutboxAgeStats(now);
            long currentP95 = stats.p95Ms();
            long currentP99 = stats.p99Ms();

            p95Ms.set(currentP95);
            p99Ms.set(currentP99);

            long slope = currentP95 - lastP95Ms;
            slopeMsPer10s.set(slope);
            lastP95Ms = currentP95;

            // Actuator percentile 계산용 샘플 (고카디널리티 없음)
            outboxAgeSummary.record(currentP95);
            outboxAgeSummary.record(currentP99);
        } catch (Exception e) {
            log.warn("[OutboxAgeMetrics] failed to collect outbox age metrics: {}", e.toString());
        }
    }
}
