package com.yunhwan.auth.error.outbox.support.impl;

import com.yunhwan.auth.error.config.outbox.OutboxProperties;
import com.yunhwan.auth.error.outbox.support.api.RetryPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

/**
 * FixedDelayRetryPolicy
 * <p>
 * 역할:
 * - {@link RetryPolicy}의 구현체로, 고정된 시간 간격으로 재시도를 수행하는 전략을 제공합니다.
 * - 설정된 최대 재시도 횟수와 딜레이 시간을 기반으로 동작합니다.
 */
@Component
@RequiredArgsConstructor
public class FixedDelayRetryPolicy implements RetryPolicy {

    private final OutboxProperties outboxProperties;

    @Override
    public int nextRetryCount(int currentRetryCount) {
        return currentRetryCount + 1;
    }

    @Override
    public boolean shouldDead(int nextRetryCount) {
        int maxRetries = outboxProperties.getRetry().getMaxRetries();
        return nextRetryCount >= maxRetries;
    }

    @Override
    public OffsetDateTime nextRetryAt(OffsetDateTime now, int nextRetryCount) {
        int retryDelaySeconds = outboxProperties.getRetry().getDelaySeconds();
        return now.plusSeconds(retryDelaySeconds);
    }
}
