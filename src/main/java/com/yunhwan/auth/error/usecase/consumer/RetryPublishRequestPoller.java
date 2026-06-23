package com.yunhwan.auth.error.usecase.consumer;

import com.yunhwan.auth.error.domain.consumer.RetryPublishRequest;
import com.yunhwan.auth.error.usecase.outbox.config.OutboxProperties;
import com.yunhwan.auth.error.usecase.outbox.port.OwnerResolver;
import com.yunhwan.auth.error.usecase.consumer.port.RetryPublishRequestStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class RetryPublishRequestPoller {

    private final RetryPublishRequestStore store;
    private final OutboxProperties outboxProperties;
    private final OwnerResolver ownerResolver;
    private final Clock clock;

    @Transactional
    public RetryPublishRequestClaimResult pollOnce() {
        String owner = ownerResolver.resolve();
        int batchSize = outboxProperties.getPoller().getBatchSize();
        List<RetryPublishRequest> claimed = store.claimBatch(batchSize, owner, OffsetDateTime.now(clock));
        return new RetryPublishRequestClaimResult(owner, claimed);
    }
}
