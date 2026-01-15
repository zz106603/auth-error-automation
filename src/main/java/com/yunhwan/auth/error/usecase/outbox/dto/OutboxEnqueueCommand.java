package com.yunhwan.auth.error.usecase.outbox.dto;

public record OutboxEnqueueCommand(
        String aggregateType,
        String aggregateId,
        String eventType,
        String payloadJson,
        String idempotencyKey
) {
}
