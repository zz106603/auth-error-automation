package com.yunhwan.auth.error.stub;

import com.yunhwan.auth.error.domain.outbox.OutboxMessage;
import com.yunhwan.auth.error.outbox.publisher.OutboxPublisher;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

@Profile("stub")
@Primary
@Component
public class TestOutboxPublisher implements OutboxPublisher {

    private final AtomicBoolean shouldFail = new AtomicBoolean(false);

    public void failNext(boolean v) {
        shouldFail.set(v);
    }

    @Override
    public void publish(OutboxMessage message) {
        if (shouldFail.get()) {
            throw new RuntimeException("Test exception");
        }
    }
}
