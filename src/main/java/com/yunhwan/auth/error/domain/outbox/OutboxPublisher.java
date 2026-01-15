package com.yunhwan.auth.error.domain.outbox;

public interface OutboxPublisher {
    void publish(OutboxMessage message) throws Exception;
}
