package com.yunhwan.auth.error.testsupport.fixtures;

import com.yunhwan.auth.error.domain.outbox.OutboxMessage;
import com.yunhwan.auth.error.usecase.outbox.port.OutboxMessageStore;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Optional;

@Profile("test")
@Component
public class OutboxFixtures {

    private final OutboxMessageStore outboxMessageStore;
    private final Clock clock;

    public OutboxFixtures(OutboxMessageStore outboxMessageStore, Clock clock) {
        this.outboxMessageStore = outboxMessageStore;
        this.clock = clock;
    }

    public OutboxMessage createAuthErrorMessage(String scopePrefixOrNull, String rawReqId, String payload) {
        String scopedReqId = Optional.ofNullable(scopePrefixOrNull).orElse("") + rawReqId;

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
