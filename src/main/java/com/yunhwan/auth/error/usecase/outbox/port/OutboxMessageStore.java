package com.yunhwan.auth.error.usecase.outbox.port;

import com.yunhwan.auth.error.domain.autherror.AuthError;
import com.yunhwan.auth.error.domain.outbox.OutboxMessage;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface OutboxMessageStore {

    OutboxMessage save(OutboxMessage outboxMessage);

    Optional<OutboxMessage> findById(Long id);

    Optional<OutboxMessage> findByIdempotencyKey(String idempotencyKey);

    List<OutboxMessage> findAllById(Set<Long> union);

    boolean existsByIdempotencyKey(String idempotencyKey);

    OutboxMessage upsertReturning(
            String aggregateType,
            String aggregateId,
            String eventType,
            String payloadJson,
            String idempotencyKey,
            OffsetDateTime now
    );

    List<OutboxMessage> claimBatch(
            int batchSize,
            String owner,
            OffsetDateTime now,
            String scopePrefix
    );

    int setNextRetryAt(Long id, OffsetDateTime nextRetryAt, OffsetDateTime now);

    int markPublished(long id, String owner, OffsetDateTime now);

    int markForRetry(long id, String owner, int retryCount, OffsetDateTime nextRetryAt, String lastError, OffsetDateTime now);

    int markDead(long id, String owner, int retryCount, String lastError, OffsetDateTime now);

    List<OutboxMessage> pickStaleProcessing(OffsetDateTime staleBefore, int batchSize, String scopePrefix);

    int takeoverStaleProcessing(long id, String newOwner, OffsetDateTime now, OffsetDateTime staleBefore);

}
