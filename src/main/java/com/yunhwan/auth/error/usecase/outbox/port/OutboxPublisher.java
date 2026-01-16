package com.yunhwan.auth.error.usecase.outbox.port;

import com.yunhwan.auth.error.domain.outbox.OutboxMessage;

public interface OutboxPublisher {
    void publish(OutboxMessage message) throws Exception;
}
