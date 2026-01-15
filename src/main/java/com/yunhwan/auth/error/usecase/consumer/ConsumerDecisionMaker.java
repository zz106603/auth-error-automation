package com.yunhwan.auth.error.usecase.consumer;

import com.yunhwan.auth.error.common.exception.NonRetryableAuthErrorException;
import com.yunhwan.auth.error.domain.outbox.decision.OutboxDecision;
import com.yunhwan.auth.error.domain.outbox.policy.RetryPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

@Component
@RequiredArgsConstructor
public class ConsumerDecisionMaker {

    private final RetryPolicy retryPolicy;

    public OutboxDecision decide(OffsetDateTime now, int currentRetryCount, Throwable e) {
        String lastError = compactError(e);
        int nextRetry = retryPolicy.nextRetryCount(currentRetryCount);

        // 비재시도 예외이거나 정책상 더 이상 재시도하지 않으면 DEAD
        if (e instanceof NonRetryableAuthErrorException || retryPolicy.shouldDead(nextRetry)) {
            return OutboxDecision.ofDead(nextRetry, lastError);
        }

        // 재시도
        return OutboxDecision.ofRetry(nextRetry, retryPolicy.nextRetryAt(now, nextRetry), lastError);
    }

    private String compactError(Throwable e) {
        String msg = e.getMessage();
        if (msg == null || msg.isBlank()) msg = e.getClass().getSimpleName();
        // 너무 길면 로그/헤더 폭발 방지
        return (msg.length() > 300) ? msg.substring(0, 300) : msg;
    }
}
