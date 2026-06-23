package com.yunhwan.auth.error.usecase.consumer;

import com.yunhwan.auth.error.domain.consumer.RetryPublishRequest;

import java.util.List;

public record RetryPublishRequestClaimResult(
        String owner,
        List<RetryPublishRequest> claimed
) {
}
