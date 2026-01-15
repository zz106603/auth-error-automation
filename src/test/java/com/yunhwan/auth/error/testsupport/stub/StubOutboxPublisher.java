package com.yunhwan.auth.error.testsupport.stub;

import com.yunhwan.auth.error.domain.outbox.OutboxMessage;
import com.yunhwan.auth.error.domain.outbox.OutboxPublisher;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

@Profile("stub")
@Primary
@Component
public class StubOutboxPublisher implements OutboxPublisher {

    private final AtomicBoolean shouldFail = new AtomicBoolean(false);

    public void failNext(boolean v) {
        shouldFail.set(v);
    }

    @Override
    public void publish(OutboxMessage message) {
        if (shouldFail.getAndSet(false)) {
            throw new RuntimeException("Test exception");
        }
    }
}
