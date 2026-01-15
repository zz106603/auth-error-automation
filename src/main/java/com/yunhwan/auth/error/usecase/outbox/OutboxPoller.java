package com.yunhwan.auth.error.usecase.outbox;

import com.yunhwan.auth.error.domain.outbox.OutboxMessage;
import com.yunhwan.auth.error.usecase.outbox.config.OutboxProperties;
import com.yunhwan.auth.error.usecase.outbox.port.OwnerResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class OutboxPoller {

    private final OutboxClaimer outboxClaimer;
    private final OwnerResolver ownerResolver;
    private final OutboxProperties props;

    /** 한 번 돌 때: PENDING -> PROCESSING으로 "claim"만 한다 */
    public List<OutboxMessage> pollOnce() {
        String owner = ownerResolver.resolve();
        int batchSize = props.getPoller().getBatchSize();

        return outboxClaimer.claimBatch(batchSize, owner);
    }
}
