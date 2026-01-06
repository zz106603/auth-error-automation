package com.yunhwan.auth.error.outbox.publisher;

import com.yunhwan.auth.error.domain.outbox.OutboxMessage;

public interface OutboxPublisher {
    void publish(OutboxMessage message) throws Exception;
}
