package com.yunhwan.auth.error.infra.persistence.adapter;

import com.yunhwan.auth.error.domain.consumer.ProcessedMessage;
import com.yunhwan.auth.error.infra.persistence.jpa.ProcessedMessageJpaRepository;
import com.yunhwan.auth.error.usecase.consumer.port.ProcessedMessageStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
@Transactional
public class ProcessedMessageStoreAdapter implements ProcessedMessageStore {

    private final ProcessedMessageJpaRepository repo;

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
    public int claimProcessing(long outboxId, OffsetDateTime now, OffsetDateTime leaseUntil, String status) {
        return repo.claimProcessing(outboxId, now, leaseUntil, status);
    }

    @Override
    public int markDone(long outboxId, OffsetDateTime now, String doneStatus, String processingStatus) {
        return repo.markDone(outboxId, now, doneStatus, processingStatus);
    }

    @Override
    public int releaseLeaseForRetry(long outboxId, OffsetDateTime now, String status) {
        return repo.releaseLeaseForRetry(outboxId, now, status);
    }
}
