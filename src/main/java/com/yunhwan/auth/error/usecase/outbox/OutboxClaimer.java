package com.yunhwan.auth.error.usecase.outbox;

import com.yunhwan.auth.error.domain.outbox.OutboxMessage;
import com.yunhwan.auth.error.usecase.outbox.port.OutboxMessageStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class OutboxClaimer {

    private final OutboxMessageStore outboxMessageStore;
    private final Clock clock;

    @Transactional
    public List<OutboxMessage> claimBatch(int batchSize, String owner, String scopePrefixOrNull) {
        return outboxMessageStore.claimBatch(batchSize, owner, OffsetDateTime.now(clock), scopePrefixOrNull);
    }
}
