package com.yunhwan.auth.error.outbox.service;

import com.yunhwan.auth.error.domain.outbox.OutboxMessage;
import com.yunhwan.auth.error.outbox.persistence.OutboxMessageRepository;
import com.yunhwan.auth.error.outbox.support.OutboxTestScope;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class OutboxClaimer {

    private final OutboxMessageRepository outboxMessageRepo;
    private final Clock clock;
    private final ObjectProvider<OutboxTestScope> testScopeProvider;

    private String scopePrefixOrNull() {
        OutboxTestScope scope = testScopeProvider.getIfAvailable();
        return scope == null ? null : scope.get();
    }

    @Transactional
    public List<OutboxMessage> claimBatch(int batchSize, String owner) {
        return outboxMessageRepo.claimBatch(batchSize, owner, OffsetDateTime.now(clock), scopePrefixOrNull());
    }
}
