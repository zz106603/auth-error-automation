package com.yunhwan.auth.error.usecase.outbox.dto;

public record OutboxAgeStats(
        long p95Ms,
        long p99Ms
) {
}
