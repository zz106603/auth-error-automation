package com.yunhwan.auth.error.outbox.support;

import java.time.OffsetDateTime;

public record OutboxDecision(
        Outcome outcome,
        int nextRetryCount,
        OffsetDateTime nextRetryAt,
        String lastError
) {
    public enum Outcome { PUBLISHED, RETRY, DEAD }

    public static OutboxDecision ofPublished() {
        return new OutboxDecision(Outcome.PUBLISHED, 0, null, null);
    }

    public static OutboxDecision ofDead(int nextRetryCount, String lastError) {
        return new OutboxDecision(Outcome.DEAD, nextRetryCount, null, lastError);
    }

    public static OutboxDecision ofRetry(int nextRetryCount, OffsetDateTime nextRetryAt, String lastError) {
        return new OutboxDecision(Outcome.RETRY, nextRetryCount, nextRetryAt, lastError);
    }

    public boolean isPublished() { return outcome == Outcome.PUBLISHED; }
    public boolean isDead() { return outcome == Outcome.DEAD; }
    public boolean isRetry() { return outcome == Outcome.RETRY; }
}
