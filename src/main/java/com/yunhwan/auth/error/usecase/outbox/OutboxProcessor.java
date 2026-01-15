package com.yunhwan.auth.error.usecase.outbox;

import com.yunhwan.auth.error.domain.outbox.OutboxMessage;
import com.yunhwan.auth.error.domain.outbox.OutboxPublisher;
import com.yunhwan.auth.error.domain.outbox.decision.OutboxDecision;
import com.yunhwan.auth.error.domain.outbox.policy.RetryPolicy;
import com.yunhwan.auth.error.usecase.outbox.port.OutboxMessageStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxProcessor {

    private final OutboxPublisher outboxPublisher;
    private final OutboxMessageStore outboxMessageStore;
    private final RetryPolicy retryPolicy;
    private final Clock clock;

    /**
     * poller가 claim해서 PROCESSING으로 바꾼 메시지들을 처리하고,
     * 결과에 따라 PUBLISHED / PENDING(재시도) / DEAD 로 마무리한다.
     */
    public int process(List<OutboxMessage> claimed) {
        if (claimed.isEmpty()) return 0;

        OffsetDateTime now = OffsetDateTime.now(clock);
        int affected = 0;

        for (OutboxMessage m : claimed) {
            OutboxDecision decision;

            try {
                // 1) 실제 처리(예: RabbitMQ publish)
                outboxPublisher.publish(m);

                // 2) 성공 -> PUBLISHED로 마감
                decision = OutboxDecision.ofPublished();
            } catch (Exception e) {
                decision = decideFailure(m, e, now);
            }
            affected += applyDecision(m, decision, now);
        }
        return affected;
    }

    private OutboxDecision decideFailure(OutboxMessage m, Exception e, OffsetDateTime now) {
        int nextRetryCount = retryPolicy.nextRetryCount(m.getRetryCount());
        String err = truncate(e.getClass().getSimpleName() + ": " + safeMsg(e), 1000);

        if (retryPolicy.shouldDead(nextRetryCount)) {
            return OutboxDecision.ofDead(nextRetryCount, err);
        }

        OffsetDateTime nextRetryAt = retryPolicy.nextRetryAt(now, nextRetryCount);
        return OutboxDecision.ofRetry(nextRetryCount, nextRetryAt, err);
    }

    private int applyDecision(OutboxMessage m, OutboxDecision decision, OffsetDateTime now) {
        if (decision.isPublished()) {
            return outboxMessageStore.markPublished(m.getId(), now);
        }

        if (decision.isDead()) {
            return outboxMessageStore.markDead(
                    m.getId(),
                    decision.nextRetryCount(),
                    decision.lastError(),
                    now
            );
        }

        return outboxMessageStore.markForRetry(
                m.getId(),
                decision.nextRetryCount(),
                decision.nextRetryAt(),
                decision.lastError(),
                now
        );
    }

    private String safeMsg(Exception e) {
        return e.getMessage() == null ? "" : e.getMessage();
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen);
    }
}
