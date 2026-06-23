package com.yunhwan.auth.error.infra.persistence.adapter;

import com.yunhwan.auth.error.domain.consumer.RetryPublishRequest;
import com.yunhwan.auth.error.infra.persistence.jpa.RetryPublishRequestJpaRepository;
import com.yunhwan.auth.error.usecase.consumer.port.RetryPublishRequestStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
@Transactional
public class RetryPublishRequestStoreAdapter implements RetryPublishRequestStore {

    private final RetryPublishRequestJpaRepository repo;

    @Override
    public RetryPublishRequest enqueue(long sourceOutboxId, String eventType, String aggregateType, String payload, int retryCount, OffsetDateTime nextRetryAt, String lastError, OffsetDateTime now) {
        return repo.enqueue(sourceOutboxId, eventType, aggregateType, payload, retryCount, nextRetryAt, lastError, now);
    }

    @Override
    public List<RetryPublishRequest> claimBatch(int batchSize, String owner, OffsetDateTime now) {
        return repo.claimBatch(batchSize, owner, now);
    }

    @Override
    public int markPublished(long id, String owner, OffsetDateTime now) {
        return repo.markPublished(id, owner, now);
    }

    @Override
    public int markForRetry(long id, String owner, int publishRetryCount, OffsetDateTime nextPublishAt, String lastError, OffsetDateTime now) {
        return repo.markForRetry(id, owner, publishRetryCount, nextPublishAt, lastError, now);
    }

    @Override
    public int markDead(long id, String owner, int publishRetryCount, String lastError, OffsetDateTime now) {
        return repo.markDead(id, owner, publishRetryCount, lastError, now);
    }

    @Override
    public Optional<RetryPublishRequest> findById(long id) {
        return repo.findById(id);
    }

    @Override
    public long count() {
        return repo.count();
    }

    @Override
    public void deleteAll() {
        repo.deleteAll();
    }
}
