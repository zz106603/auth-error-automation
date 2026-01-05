package com.yunhwan.auth.error.domain.outbox;

public enum OutboxStatus {
    PENDING,
    PROCESSING,
    PUBLISHED,
    DEAD
}
