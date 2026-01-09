package com.yunhwan.auth.error.outbox.support.api;

import java.time.OffsetDateTime;

/**
 * RetryPolicy
 * <p>
 * 역할:
 * - 재시도 로직(다음 시도 횟수, 재시도 여부, 다음 실행 시간 등)을 추상화한 인터페이스입니다.
 * - {@link com.yunhwan.auth.error.outbox.service.OutboxReaper}나 메시지 프로세서가
 *   구체적인 재시도 전략(고정 딜레이, 지수 백오프 등)을 몰라도 되도록 분리합니다.
 */
public interface RetryPolicy {

    /**
     * 현재 재시도 횟수를 기반으로 다음 재시도 횟수를 계산합니다.
     */
    int nextRetryCount(int currentRetryCount);

    /**
     * 다음 재시도 횟수를 기준으로 더 이상 재시도하지 않고 실패(Dead) 처리해야 하는지 판단합니다.
     */
    boolean shouldDead(int nextRetryCount);

    /**
     * 다음 재시도 실행 시간을 계산합니다.
     */
    OffsetDateTime nextRetryAt(OffsetDateTime now, int nextRetryCount);
}
