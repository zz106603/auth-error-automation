package com.yunhwan.auth.error.outbox.service;

import com.yunhwan.auth.error.config.outbox.OutboxProperties;
import com.yunhwan.auth.error.domain.outbox.OutboxMessage;
import com.yunhwan.auth.error.outbox.persistence.OutboxMessageRepository;
import com.yunhwan.auth.error.outbox.support.ReapDecision;
import com.yunhwan.auth.error.outbox.support.api.RetryPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * OutboxReaper
 * <p>
 * 역할:
 * - 오랫동안 'PROCESSING' 상태로 멈춰있는(Stale) 메시지를 찾아 처리합니다.
 * - 처리가 중단된 것으로 간주되는 메시지를 찾아, 재시도(Retry)할지 혹은 실패(Dead) 처리할지 결정합니다.
 * - 실제 재시도 정책 판단은 {@link RetryPolicy}에게 위임합니다.
 */
@Service
@RequiredArgsConstructor
public class OutboxReaper {

    private final OutboxMessageRepository outboxMessageRepo;
    private final OutboxProperties outboxProperties;
    private final RetryPolicy retryPolicy;
    private final Clock clock;

    @Transactional
    public int reapOnce() {
        OffsetDateTime now = OffsetDateTime.now(clock);

        int staleAfterSeconds = outboxProperties.getReaper().getStaleAfterSeconds();
        int batchSize = outboxProperties.getReaper().getBatchSize();

        OffsetDateTime staleBefore = now.minusSeconds(staleAfterSeconds);

        List<OutboxMessage> stale = outboxMessageRepo.pickStaleProcessing(staleBefore, batchSize);
        if (stale.isEmpty()) return 0;

        int affected = 0;
        for (OutboxMessage m : stale) {
            ReapDecision decision = decide(m, now, staleAfterSeconds);

            if (decision.dead()) {
                affected += outboxMessageRepo.markDead(
                        m.getId(),
                        decision.nextRetryCount(),
                        decision.lastError(),
                        now
                );
            } else {
                affected += outboxMessageRepo.markForRetry(
                        m.getId(),
                        decision.nextRetryCount(),
                        decision.nextRetryAt(),
                        decision.lastError(),
                        now
                );
            }
        }

        return affected;
    }

    private ReapDecision decide(OutboxMessage m, OffsetDateTime now, int staleAfterSeconds) {
        int nextRetryCount = retryPolicy.nextRetryCount(m.getRetryCount());
        String lastError = "STALE_PROCESSING: reaped after " + staleAfterSeconds + "s";

        if (retryPolicy.shouldDead(nextRetryCount)) {
            return ReapDecision.dead(nextRetryCount, lastError);
        }

        OffsetDateTime nextRetryAt = retryPolicy.nextRetryAt(now, nextRetryCount);
        return ReapDecision.retry(nextRetryCount, nextRetryAt, lastError);
    }
}
