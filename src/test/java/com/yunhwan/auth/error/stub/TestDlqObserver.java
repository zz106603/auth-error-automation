package com.yunhwan.auth.error.stub;

import com.yunhwan.auth.error.consumer.observer.DlqObserver;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Profile("stub")
@Component
@Primary
public class TestDlqObserver implements DlqObserver {

    private final AtomicLong count = new AtomicLong(0);
    private final AtomicReference<Long> lastOutboxId = new AtomicReference<>(null);

    @Override
    public void onDlq(Long outboxId, String payload) {
        count.incrementAndGet();
        lastOutboxId.set(outboxId);
    }

    public long count() {
        return count.get();
    }

    public Long lastOutboxId() {
        return lastOutboxId.get();
    }

    public void reset() {
        count.set(0);
        lastOutboxId.set(null);
    }
}
