package com.yunhwan.auth.error.usecase.consumer;

import com.yunhwan.auth.error.common.exception.NonRetryablePublishException;
import com.yunhwan.auth.error.domain.consumer.RetryPublishRequest;
import com.yunhwan.auth.error.usecase.consumer.port.RetryPublishRequestPublisher;
import com.yunhwan.auth.error.usecase.consumer.port.RetryPublishRequestStore;
import com.yunhwan.auth.error.usecase.consumer.policy.RetryPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class RetryPublishRequestProcessor {

    private final RetryPublishRequestPublisher publisher;
    private final RetryPublishRequestStore store;
    private final RetryPolicy retryPolicy;
    private final Clock clock;

    public int process(String owner, List<RetryPublishRequest> claimed) {
        int affected = 0;
        for (RetryPublishRequest request : claimed) {
            OffsetDateTime now = OffsetDateTime.now(clock);
            try {
                publisher.publish(request);
                affected += store.markPublished(request.getId(), owner, now);
                log.info("[RetryPublishRequest] published. id={}, outboxId={}, eventType={}, retryCount={}",
                        request.getId(), request.getSourceOutboxId(), request.getEventType(), request.getRetryCount());
            } catch (Exception e) {
                affected += handleFailure(owner, request, now, e);
            }
        }
        return affected;
    }

    private int handleFailure(String owner, RetryPublishRequest request, OffsetDateTime now, Exception e) {
        String err = compactError(e);
        int nextPublishRetry = request.getPublishRetryCount() + 1;

        if (e instanceof NonRetryablePublishException || retryPolicy.shouldDead(nextPublishRetry)) {
            log.error("[RetryPublishRequest] DEAD. id={}, outboxId={}, err={}",
                    request.getId(), request.getSourceOutboxId(), err, e);
            return store.markDead(request.getId(), owner, nextPublishRetry, err, now);
        }

        OffsetDateTime nextPublishAt = retryPolicy.nextRetryAt(now, nextPublishRetry);
        log.warn("[RetryPublishRequest] publish failed -> retry. id={}, outboxId={}, nextPublishRetry={}, nextPublishAt={}, err={}",
                request.getId(), request.getSourceOutboxId(), nextPublishRetry, nextPublishAt, err);
        return store.markForRetry(request.getId(), owner, nextPublishRetry, nextPublishAt, err, now);
    }

    private String compactError(Exception e) {
        String msg = e.getMessage();
        if (msg == null || msg.isBlank()) {
            msg = e.getClass().getSimpleName();
        }
        return msg.length() <= 1000 ? msg : msg.substring(0, 1000);
    }
}
