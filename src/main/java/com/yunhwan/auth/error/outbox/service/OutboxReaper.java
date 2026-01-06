package com.yunhwan.auth.error.outbox.service;

import com.yunhwan.auth.error.common.OwnerResolver;
import com.yunhwan.auth.error.config.OutboxProperties;
import com.yunhwan.auth.error.domain.outbox.OutboxMessage;
import com.yunhwan.auth.error.outbox.persistence.OutboxMessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class OutboxReaper {

    private final OutboxMessageRepository repo;
    private final OutboxProperties props;
    private final Clock clock;

    @Transactional
    public int reapOnce() {
        OffsetDateTime now = OffsetDateTime.now(clock);

        int staleAfterSeconds = props.getReaper().getStaleAfterSeconds();
        int batchSize = props.getReaper().getBatchSize();

        OffsetDateTime staleBefore = now.minusSeconds(staleAfterSeconds);

        List<OutboxMessage> stale = repo.pickStaleProcessing(staleBefore, batchSize);
        if (stale.isEmpty()) return 0;

        int maxRetries = props.getRetry().getMaxRetries();
        int retryDelaySeconds = props.getRetry().getDelaySeconds();

        for (OutboxMessage m : stale) {
            int nextRetryCount = m.getRetryCount() + 1;
            String err = truncate("STALE_PROCESSING: reaped after " + staleAfterSeconds + "s", 1000);

            if (nextRetryCount >= maxRetries) {
                repo.markDead(m.getId(), nextRetryCount, err, now);
            } else {
                OffsetDateTime nextRetryAt = now.plusSeconds(retryDelaySeconds);
                repo.markForRetry(m.getId(), nextRetryCount, nextRetryAt, err, now);
            }
        }

        return stale.size();
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen);
    }
}
