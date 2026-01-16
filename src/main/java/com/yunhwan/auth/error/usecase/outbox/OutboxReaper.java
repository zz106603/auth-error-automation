package com.yunhwan.auth.error.usecase.outbox;

import com.yunhwan.auth.error.domain.outbox.OutboxMessage;
import com.yunhwan.auth.error.domain.outbox.decision.OutboxDecision;
import com.yunhwan.auth.error.usecase.consumer.policy.RetryPolicy;
import com.yunhwan.auth.error.usecase.outbox.config.OutboxProperties;
import com.yunhwan.auth.error.usecase.outbox.port.OutboxMessageStore;
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

    private final OutboxMessageStore outboxMessageStore;
    private final OutboxProperties outboxProperties;
    private final RetryPolicy retryPolicy;
    private final Clock clock;

    @Transactional
    public int reapOnce(String scopePrefixOrNull) {
        OffsetDateTime now = OffsetDateTime.now(clock);

        int staleAfterSeconds = outboxProperties.getReaper().getStaleAfterSeconds();
        int batchSize = outboxProperties.getReaper().getBatchSize();

        OffsetDateTime staleBefore = now.minusSeconds(staleAfterSeconds);

        List<OutboxMessage> stale = outboxMessageStore.pickStaleProcessing(staleBefore, batchSize, scopePrefixOrNull);
        if (stale.isEmpty()) return 0;

        int affected = 0;
        for (OutboxMessage m : stale) {
            OutboxDecision decision = decide(m, now, staleAfterSeconds);

            if (decision.isDead()) {
                affected += outboxMessageStore.markDead(
                        m.getId(),
                        decision.nextRetryCount(),
                        decision.lastError(),
                        now
                );
            } else {
                affected += outboxMessageStore.markForRetry(
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

    private OutboxDecision decide(OutboxMessage m, OffsetDateTime now, int staleAfterSeconds) {
        int nextRetryCount = retryPolicy.nextRetryCount(m.getRetryCount());
        String lastError = "STALE_PROCESSING: reaped after " + staleAfterSeconds + "s";

        if (retryPolicy.shouldDead(nextRetryCount)) {
            return OutboxDecision.ofDead(nextRetryCount, lastError);
        }

        OffsetDateTime nextRetryAt = retryPolicy.nextRetryAt(now, nextRetryCount);
        return OutboxDecision.ofRetry(nextRetryCount, nextRetryAt, lastError);
    }
}
