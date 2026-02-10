package com.yunhwan.auth.error.testsupport.stub;

import com.yunhwan.auth.error.common.exception.NonRetryablePublishException;
import com.yunhwan.auth.error.common.exception.RetryablePublishException;
import com.yunhwan.auth.error.domain.outbox.OutboxMessage;
import com.yunhwan.auth.error.usecase.outbox.port.OutboxPublisher;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

@Profile("stub")
@Primary
@Component
public class StubOutboxPublisher implements OutboxPublisher {

    private enum FailMode {
        NONE,
        RETRYABLE,
        NON_RETRYABLE
    }

    private final AtomicReference<FailMode> nextFail = new AtomicReference<>(FailMode.NONE);

    public void failNext(boolean v) {
        nextFail.set(v ? FailMode.RETRYABLE : FailMode.NONE);
    }

    public void failNextRetryable() {
        nextFail.set(FailMode.RETRYABLE);
    }

    public void failNextNonRetryable() {
        nextFail.set(FailMode.NON_RETRYABLE);
    }

    @Override
    public void publish(OutboxMessage message) {
        FailMode mode = nextFail.getAndSet(FailMode.NONE);
        if (mode == FailMode.RETRYABLE) {
            throw new RetryablePublishException("Test exception", null);
        }
        if (mode == FailMode.NON_RETRYABLE) {
            throw new NonRetryablePublishException("Test exception");
        }
    }
}
