package com.yunhwan.auth.error.usecase.consumer.dto;

import com.yunhwan.auth.error.domain.consumer.DeadLetterReasonCode;
import com.yunhwan.auth.error.domain.consumer.ReplayStatus;

import java.time.OffsetDateTime;

public record DeadLetterMessageRecordCommand(
        String dedupeKey,
        String dlqQueue,
        String sourceQueue,
        String sourceExchange,
        String sourceRoutingKey,
        String deadLetterExchange,
        String deadLetterRoutingKey,
        Long outboxId,
        String eventType,
        String aggregateType,
        String payload,
        String payloadHash,
        int payloadSizeBytes,
        DeadLetterReasonCode reasonCode,
        String brokerDeathReason,
        String xDeath,
        Integer retryCount,
        String processedMessageStatusAtArrival,
        String outboxStatusAtArrival,
        ReplayStatus replayStatus,
        OffsetDateTime now
) {
}
