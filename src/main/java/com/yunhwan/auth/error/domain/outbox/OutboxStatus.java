package com.yunhwan.auth.error.domain.outbox;

public enum OutboxStatus {
    PENDING,
    PUBLISHED,
    FAILED,
    DEAD
}
