package com.yunhwan.auth.error.usecase.consumer.port;

import com.yunhwan.auth.error.domain.consumer.ProcessedMessage;

import java.time.OffsetDateTime;
import java.util.Optional;

public interface ProcessedMessageStore {

    long count();

    void deleteAll();

    Optional<ProcessedMessage> findById(Long id);

    boolean existsById(Long id);

    boolean existsByOutboxId(Long outboxId);

    int claimProcessing(long outboxId, OffsetDateTime now, OffsetDateTime leaseUntil, String status);

    int markDone(long outboxId, OffsetDateTime now, String doneStatus, String processingStatus);

    int releaseLeaseForRetry(long outboxId, OffsetDateTime now, String status);
}
