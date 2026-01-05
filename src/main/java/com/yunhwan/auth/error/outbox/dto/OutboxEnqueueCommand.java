package com.yunhwan.auth.error.outbox.dto;

public record OutboxEnqueueCommand(
        String aggregateType,
        String aggregateId,
        String eventType,
        String payloadJson,
        String idempotencyKey
) {
}
