package com.yunhwan.auth.error.outbox.service;

import com.yunhwan.auth.error.domain.outbox.OutboxMessage;
import com.yunhwan.auth.error.outbox.persistence.OutboxMessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class OutboxClaimer {
    private final OutboxMessageRepository outboxMessageRepo;

    @Transactional
    public List<OutboxMessage> claim(int batchSize, String owner) {
        return outboxMessageRepo.claimBatch(batchSize, owner);
    }
}
