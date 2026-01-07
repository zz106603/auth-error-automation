package com.yunhwan.auth.error.outbox.service;

import com.yunhwan.auth.error.common.OwnerResolver;
import com.yunhwan.auth.error.config.outbox.OutboxProperties;
import com.yunhwan.auth.error.domain.outbox.OutboxMessage;
import com.yunhwan.auth.error.outbox.persistence.OutboxMessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class OutboxPoller {

    private final OutboxMessageRepository outboxMessageRepo;
    private final OutboxClaimer outboxClaimer;
    private final OwnerResolver ownerResolver;
    private final OutboxProperties props;

    /** 한 번 돌 때: PENDING -> PROCESSING으로 "claim"만 한다 */
    public List<OutboxMessage> pollOnce() {
        String owner = ownerResolver.resolve();
        int batchSize = props.getPoller().getBatchSize();

        return outboxClaimer.claim(batchSize, owner);
    }
}
