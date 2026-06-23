package com.yunhwan.auth.error.domain.consumer;

public enum RetryPublishStatus {
    PENDING,
    PROCESSING,
    PUBLISHED,
    DEAD
}
