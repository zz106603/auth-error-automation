package com.yunhwan.auth.error.outbox.publisher;

import com.yunhwan.auth.error.domain.outbox.OutboxMessage;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

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
