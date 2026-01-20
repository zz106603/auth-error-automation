package com.yunhwan.auth.error.usecase.consumer.port;

import com.yunhwan.auth.error.domain.consumer.ProcessedMessage;
import com.yunhwan.auth.error.domain.consumer.ProcessedStatus;

import java.time.OffsetDateTime;
import java.util.Optional;

public interface ProcessedMessageStore {

    long count();

    void deleteAll();

    Optional<ProcessedMessage> findById(Long id);

    boolean existsById(Long id);

    boolean existsByOutboxId(Long outboxId);

    /**
     * 처리 선점 (DB 상태 기준)
     * - row 없으면 PENDING 생성
     */
    void ensureRowExists(long outboxId, OffsetDateTime now);

    /**
     * - PENDING/RETRY_WAIT 이고 nextRetryAt이 지났고 lease 만료면 PROCESSING으로 선점
     */
    int claimProcessingUpdate(long outboxId, OffsetDateTime now, OffsetDateTime leaseUntil);

    /**
     * 성공 확정 (PROCESSING -> DONE)
     */
    int markDone(long outboxId, OffsetDateTime now);

    /**
     * 성공 확정 (PROCESSING -> DONE)
     */
    int markRetryWait(long outboxId, OffsetDateTime now, OffsetDateTime nextRetryAt, int nextRetryCount, String lastError);

    int markDead(long outboxId, OffsetDateTime now, String lastError);

    Optional<ProcessedStatus> findStatusByOutboxId(long outboxId);
}
