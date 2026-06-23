package com.yunhwan.auth.error.usecase.consumer.port;

import com.yunhwan.auth.error.domain.consumer.RetryPublishRequest;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface RetryPublishRequestStore {

    RetryPublishRequest enqueue(
            long sourceOutboxId,
            String eventType,
            String aggregateType,
            String payload,
            int retryCount,
            OffsetDateTime nextRetryAt,
            String lastError,
            OffsetDateTime now
    );

    List<RetryPublishRequest> claimBatch(int batchSize, String owner, OffsetDateTime now);

    int markPublished(long id, String owner, OffsetDateTime now);

    int markForRetry(long id, String owner, int publishRetryCount, OffsetDateTime nextPublishAt, String lastError, OffsetDateTime now);

    int markDead(long id, String owner, int publishRetryCount, String lastError, OffsetDateTime now);

    Optional<RetryPublishRequest> findById(long id);

    long count();

    void deleteAll();
}
