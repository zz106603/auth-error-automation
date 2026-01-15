package com.yunhwan.auth.error.infra.messaging.rabbit;

import org.springframework.stereotype.Component;

/**
 * RetryRoutingResolver
 *
 * nextRetryCount(= 이번에 넣을 retry count) 기준으로
 * 어떤 retry queue(=routing key)로 보낼지 결정한다.
 *
 * ladder 예시:
 * 1~3  -> 10s
 * 4~6  -> 1m
 * 7+   -> 10m
 */
@Component
public class RetryRoutingResolver {

    private static final int FAST_RETRY_MAX = 3;
    private static final int MEDIUM_RETRY_MAX = 6;

    public String resolve(int nextRetryCount) {
        if (nextRetryCount <= 0) {
            // 방어: 0 이하가 들어오면 첫 retry로 취급
            nextRetryCount = 1;
        }

        if (nextRetryCount <= FAST_RETRY_MAX) {
            return RabbitTopologyConfig.RETRY_RK_10S;
        }

        if (nextRetryCount <= MEDIUM_RETRY_MAX) {
            return RabbitTopologyConfig.RETRY_RK_1M;
        }

        return RabbitTopologyConfig.RETRY_RK_10M;
    }
}
