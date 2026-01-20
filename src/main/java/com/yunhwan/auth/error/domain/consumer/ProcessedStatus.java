package com.yunhwan.auth.error.domain.consumer;

public enum ProcessedStatus {
    PENDING,
    PROCESSING,
    RETRY_WAIT,
    DONE,
    DEAD
}
