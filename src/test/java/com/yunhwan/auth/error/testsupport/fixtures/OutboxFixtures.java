package com.yunhwan.auth.error.testsupport.fixtures;

import com.yunhwan.auth.error.domain.outbox.OutboxMessage;
import com.yunhwan.auth.error.usecase.outbox.port.OutboxMessageStore;
import com.yunhwan.auth.error.usecase.outbox.port.OutboxScopeResolver;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.OffsetDateTime;

@Profile("test")
@Component
public class OutboxFixtures {

    private final OutboxMessageStore outboxMessageStore;
    private final Clock clock;
    private final OutboxScopeResolver outboxScopeResolver;

    public OutboxFixtures(OutboxMessageStore outboxMessageStore, Clock clock, OutboxScopeResolver outboxScopeResolver) {
        this.outboxMessageStore = outboxMessageStore;
        this.clock = clock;
        this.outboxScopeResolver = outboxScopeResolver;
    }

    public OutboxMessage createAuthErrorMessage(String rawReqId, String payload) {
        String scopedReqId = outboxScopeResolver.scopePrefixOrNull() + rawReqId;

        return outboxMessageStore.upsertReturning(
                "AUTH_ERROR",
                scopedReqId,
                "AUTH_ERROR_DETECTED_V1",
                payload,
                "AUTH_ERROR:" + scopedReqId + ":AUTH_ERROR_DETECTED_V1",
                OffsetDateTime.now(clock)
        );
    }


}
