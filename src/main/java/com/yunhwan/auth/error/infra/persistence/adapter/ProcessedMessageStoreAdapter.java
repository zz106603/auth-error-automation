package com.yunhwan.auth.error.infra.persistence.adapter;

import com.yunhwan.auth.error.domain.consumer.ProcessedMessage;
import com.yunhwan.auth.error.domain.consumer.ProcessedStatus;
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
    public void ensureRowExists(long outboxId, OffsetDateTime now) {
        repo.ensureRowExists(outboxId, now);
    }

    @Override
    public int claimProcessingUpdate(long outboxId, OffsetDateTime now, OffsetDateTime leaseUntil) {
        return repo.claimProcessingUpdate(outboxId, now, leaseUntil);
    }

    @Override
    public int markDone(long outboxId, OffsetDateTime now) {
        return repo.markDone(outboxId, now);
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
}
