package com.yunhwan.auth.error.usecase.consumer.dto;

public record DeadLetterSourceSnapshot(
        String processedMessageStatus,
        String processedMessageLastError,
        Integer processedMessageRetryCount,
        String outboxStatus
) {
}
