package com.yunhwan.auth.error.infra.persistence.adapter;

import com.yunhwan.auth.error.domain.consumer.ProcessedMessage;
import com.yunhwan.auth.error.domain.consumer.ProcessedStatus;
import com.yunhwan.auth.error.infra.metrics.MetricsConfig;
import com.yunhwan.auth.error.infra.metrics.RecordedConsumerMetricsContext;
import com.yunhwan.auth.error.infra.persistence.jpa.ProcessedMessageJpaRepository;
import com.yunhwan.auth.error.usecase.consumer.port.ProcessedMessageStore;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Repository
@RequiredArgsConstructor
@Transactional
public class ProcessedMessageStoreAdapter implements ProcessedMessageStore {

    private final ProcessedMessageJpaRepository repo;
    private final MeterRegistry meterRegistry;

    @Override
    public long count() {
        return repo.count();
    }

    @Override
    public void deleteAll(){
        repo.deleteAll();
    }

    @Override
    public Optional<ProcessedMessage> findById(Long id){
        return repo.findById(id);
    }

    @Override
    public boolean existsById(Long id) {
        return repo.existsById(id);
    }

    @Override
    public boolean existsByOutboxId(Long outboxId) {
        return repo.existsByOutboxId(outboxId);
    }

    @Override
    public void ensureRowExists(long outboxId, OffsetDateTime now) {
        recordRecordedPathTimer(MetricsConfig.METRIC_PROCESSED_MESSAGE_ENSURE_ROW_EXISTS, () -> {
            repo.ensureRowExists(outboxId, now);
            return null;
        });
    }

    @Override
    public int claimProcessingUpdate(long outboxId, OffsetDateTime now, OffsetDateTime leaseUntil) {
        return recordRecordedPathTimer(
                MetricsConfig.METRIC_PROCESSED_MESSAGE_CLAIM_PROCESSING_UPDATE,
                () -> repo.claimProcessingUpdate(outboxId, now, leaseUntil)
        );
    }

    @Override
    public int markDone(long outboxId, OffsetDateTime now) {
        return recordRecordedPathTimer(
                MetricsConfig.METRIC_PROCESSED_MESSAGE_MARK_DONE,
                () -> repo.markDone(outboxId, now)
        );
    }

    @Override
    public int markRetryWait(long outboxId, OffsetDateTime now, OffsetDateTime nextRetryAt, int nextRetryCount, String lastError){
        return repo.markRetryWait(outboxId, now, nextRetryAt, nextRetryCount, lastError);
    }

    @Override
    public int markDead(long outboxId, OffsetDateTime now, String lastError) {
        return repo.markDead(outboxId, now, lastError);
    }

    @Override
    public Optional<ProcessedStatus> findStatusByOutboxId(long outboxId) {
        return Optional.ofNullable(repo.findStatusByOutboxId(outboxId));
    }

    private <T> T recordRecordedPathTimer(String metricName, TimedSupplier<T> supplier) {
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
                    .tag(MetricsConfig.TAG_EVENT_TYPE, context.eventType())
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
