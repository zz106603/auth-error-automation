package com.yunhwan.auth.error.outbox.service;

import com.yunhwan.auth.error.config.outbox.OutboxProperties;
import com.yunhwan.auth.error.domain.outbox.OutboxMessage;
import com.yunhwan.auth.error.outbox.persistence.OutboxMessageRepository;
import com.yunhwan.auth.error.outbox.publisher.OutboxPublisher;
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
    private final OutboxMessageRepository outboxMessageRepo;
    private final OutboxProperties props;
    private final Clock clock;

    /**
     * poller가 claim해서 PROCESSING으로 바꾼 메시지들을 처리하고,
     * 결과에 따라 PUBLISHED / PENDING(재시도) / DEAD 로 마무리한다.
     */
    public void process(List<OutboxMessage> claimed) {
        for (OutboxMessage m : claimed) {
            try {
                // 1) 실제 처리(예: RabbitMQ publish)
                outboxPublisher.publish(m);

                // 2) 성공 -> PUBLISHED로 마감
                outboxMessageRepo.markPublished(m.getId(), OffsetDateTime.now(clock));

            } catch (Exception e) {
                handleFailure(m, e);
            }
        }
    }

    private void handleFailure(OutboxMessage m, Exception e) {
        int nextRetryCount = m.getRetryCount() + 1;
        String err = truncate(e.getClass().getSimpleName() + ": " + safeMsg(e), 1000);
        OffsetDateTime now = OffsetDateTime.now(clock);

        int maxRetries = props.getRetry().getMaxRetries();
        int retryDelaySeconds = props.getRetry().getDelaySeconds();

        if (nextRetryCount >= maxRetries) {
            // 포기 -> DEAD
            outboxMessageRepo.markDead(m.getId(), nextRetryCount, err, now);
            return;
        }

        // 재시도 -> PENDING + next_retry_at 설정
        OffsetDateTime nextRetryAt = now.plusSeconds(retryDelaySeconds);
        outboxMessageRepo.markForRetry(m.getId(), nextRetryCount, nextRetryAt, err, now);
    }

    private String safeMsg(Exception e) {
        return e.getMessage() == null ? "" : e.getMessage();
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen);
    }
}
