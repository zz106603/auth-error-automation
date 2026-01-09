package com.yunhwan.auth.error.outbox.support;

import java.time.OffsetDateTime;

/**
 * ReapDecision
 * <p>
 * 역할:
 * - {@link com.yunhwan.auth.error.outbox.service.OutboxReaper}가 메시지를 처리한 후 내린 결정(결과)을 담는 불변 객체(Record)입니다.
 * - 메시지를 'Dead' 상태로 보낼지, 아니면 다시 'Retry' 대기 상태로 보낼지에 대한 판단 결과를 캡슐화합니다.
 */
public record ReapDecision(
        boolean dead,
        int nextRetryCount,
        OffsetDateTime nextRetryAt,
        String lastError
) {
    /**
     * 더 이상 재시도하지 않고 실패(Dead) 처리하기 위한 결정을 생성합니다.
     */
    public static ReapDecision dead(int nextRetryCount, String lastError) {
        return new ReapDecision(true, nextRetryCount, null, lastError);
    }

    /**
     * 다시 시도(Retry)하기 위한 결정을 생성합니다.
     */
    public static ReapDecision retry(int nextRetryCount, OffsetDateTime nextRetryAt, String lastError) {
        return new ReapDecision(false, nextRetryCount, nextRetryAt, lastError);
    }
}
