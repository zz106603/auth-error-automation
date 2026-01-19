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

    private final RabbitRetryProperties props;

    public RetryRoutingResolver(RabbitRetryProperties props) {
        this.props = props;
    }

    public String resolve(int nextRetryCount) {
        if (nextRetryCount <= 0) nextRetryCount = 1;

        if (nextRetryCount <= props.getFastMax()) {
            return RabbitTopologyConfig.RETRY_RK_10S;
        }
        if (nextRetryCount <= props.getMediumMax()) {
            return RabbitTopologyConfig.RETRY_RK_1M;
        }
        return RabbitTopologyConfig.RETRY_RK_10M;
    }
}
