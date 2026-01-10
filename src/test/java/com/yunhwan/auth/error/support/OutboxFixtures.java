package com.yunhwan.auth.error.support;

import com.yunhwan.auth.error.domain.outbox.OutboxMessage;
import com.yunhwan.auth.error.outbox.persistence.OutboxMessageRepository;
import com.yunhwan.auth.error.outbox.support.OutboxTestScope;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.OffsetDateTime;

@Profile("test")
@Component
public class OutboxFixtures {

    private final OutboxMessageRepository repo;
    private final Clock clock;
    private final OutboxTestScope scope;

    public OutboxFixtures(OutboxMessageRepository repo, Clock clock, OutboxTestScope scope) {
        this.repo = repo;
        this.clock = clock;
        this.scope = scope;
    }

    public OutboxMessage createAuthErrorMessage(String rawReqId, String payload) {
        String scopedReqId = scope.get() + rawReqId;

        return repo.upsertReturning(
                "AUTH_ERROR",
                scopedReqId,
                "AUTH_ERROR_DETECTED_V1",
                payload,
                "AUTH_ERROR:" + scopedReqId + ":AUTH_ERROR_DETECTED_V1",
                OffsetDateTime.now(clock)
        );
    }


}
