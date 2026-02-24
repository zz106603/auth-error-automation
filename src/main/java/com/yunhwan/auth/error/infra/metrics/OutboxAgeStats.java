package com.yunhwan.auth.error.infra.metrics;

// outbox backlog age (p95/p99) 스냅샷
public record OutboxAgeStats(
        long p95Ms,
        long p99Ms
) {}
