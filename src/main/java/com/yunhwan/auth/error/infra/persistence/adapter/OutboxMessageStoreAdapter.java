package com.yunhwan.auth.error.infra.persistence.adapter;

import com.yunhwan.auth.error.domain.outbox.OutboxMessage;
import com.yunhwan.auth.error.infra.metrics.MetricsConfig;
import com.yunhwan.auth.error.infra.metrics.OutboxAgeStats;
import com.yunhwan.auth.error.infra.metrics.RecordedConsumerMetricsContext;
import com.yunhwan.auth.error.infra.persistence.jpa.OutboxJpaRepository;
import com.yunhwan.auth.error.usecase.outbox.port.OutboxMessageStore;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Repository
@RequiredArgsConstructor
@Transactional
public class OutboxMessageStoreAdapter implements OutboxMessageStore {

    private final OutboxJpaRepository repo;
    private final MeterRegistry meterRegistry;

    @Override
    public OutboxMessage save(OutboxMessage outboxMessage) {
        return repo.save(outboxMessage);
    }

    @Override
    public Optional<OutboxMessage> findById(Long id) {
        return repo.findById(id);
    }

    @Override
    public Optional<OutboxMessage> findByIdempotencyKey(String idempotencyKey) {
        return repo.findByIdempotencyKey(idempotencyKey);
    }

    @Override
    public List<OutboxMessage> findAllById(Set<Long> union) {
        return repo.findAllById(union);
    }

    @Override
    public boolean existsByIdempotencyKey(String idempotencyKey) {
        return repo.existsByIdempotencyKey(idempotencyKey);
    }

    @Override
    public OutboxMessage upsertReturning(String aggregateType, String aggregateId, String eventType,
                                         String payloadJson, String idempotencyKey, OffsetDateTime now) {
        return recordRecordedPathTimer(
                MetricsConfig.METRIC_OUTBOX_UPSERT_RETURNING,
                eventType,
                () -> repo.upsertReturning(aggregateType, aggregateId, eventType, payloadJson, idempotencyKey, now)
        );
    }

    @Override
    public List<OutboxMessage> claimBatch(int batchSize, String owner, OffsetDateTime now, String scopePrefix) {
        return repo.claimBatch(batchSize, owner, now, scopePrefix);
    }

    @Override
    public int setNextRetryAt(Long id, OffsetDateTime nextRetryAt, OffsetDateTime now) {
        return repo.setNextRetryAt(id, nextRetryAt, now);
    }

    @Override
    public int markPublished(long id, String owner, OffsetDateTime now) {

        return repo.markPublished(id, owner, now);
    }

    @Override
    public int markForRetry(long id, String owner, int retryCount, OffsetDateTime nextRetryAt, String lastError, OffsetDateTime now) {
        return repo.markForRetry(id, owner, retryCount, nextRetryAt, lastError, now);
    }

    @Override
    public int markDead(long id, String owner, int retryCount, String lastError, OffsetDateTime now) {
        return repo.markDead(id, owner, retryCount, lastError, now);
    }

    @Override
    public List<OutboxMessage> pickStaleProcessing(OffsetDateTime staleBefore, int batchSize, String scopePrefix) {
        return repo.pickStaleProcessing(staleBefore, batchSize, scopePrefix);
    }

    @Override
    public int takeoverStaleProcessing(long id, String newOwner, OffsetDateTime now, OffsetDateTime staleBefore) {
        return repo.takeoverStaleProcessing(id, newOwner, now, staleBefore);
    }

    @Override
    // backlog age(p95/p99) 조회용 (STOP 5.2)
    public OutboxAgeStats loadOutboxAgeStats(OffsetDateTime now) {
        Object[] row = repo.findOutboxAgeP95P99Ms(now);
        if (row == null || row.length < 2) {
            return new OutboxAgeStats(0, 0);
        }
        long p95 = toLong(row[0]);
        long p99 = toLong(row[1]);
        return new OutboxAgeStats(p95, p99);
    }

    private long toLong(Object v) {
        if (v == null) return 0;
        if (v instanceof Number n) return n.longValue();
        return Long.parseLong(Objects.toString(v));
    }

    private <T> T recordRecordedPathTimer(String metricName, String eventType, TimedSupplier<T> supplier) {
        RecordedConsumerMetricsContext.MetricContext context =
                RecordedConsumerMetricsContext.current().orElse(null);
        if (context == null) {
            return supplier.get();
        }

        long startedAt = System.nanoTime();
        try {
            return supplier.get();
        } finally {
            Timer.builder(metricName)
                    .tag(MetricsConfig.TAG_EVENT_TYPE, eventType)
                    .tag(MetricsConfig.TAG_QUEUE, context.queue())
                    .register(meterRegistry)
                    .record(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS);
        }
    }

    @FunctionalInterface
    private interface TimedSupplier<T> {
        T get();
    }
}
