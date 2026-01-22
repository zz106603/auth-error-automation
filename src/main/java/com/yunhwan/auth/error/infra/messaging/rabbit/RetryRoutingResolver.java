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

    public String resolve(String eventType, int nextRetryCount) {
        if (nextRetryCount <= 0) nextRetryCount = 1;

        boolean fast = nextRetryCount <= props.getFastMax();
        boolean medium = nextRetryCount <= props.getMediumMax();

        boolean analysis = RabbitTopologyConfig.RK_ANALYSIS_REQUESTED.equals(eventType);

        if (analysis) {
            if (fast) return RabbitTopologyConfig.RETRY_RK_ANALYSIS_10S;
            if (medium) return RabbitTopologyConfig.RETRY_RK_ANALYSIS_1M;
            return RabbitTopologyConfig.RETRY_RK_ANALYSIS_10M;
        }

        if (fast) return RabbitTopologyConfig.RETRY_RK_RECORDED_10S;
        if (medium) return RabbitTopologyConfig.RETRY_RK_RECORDED_1M;
        return RabbitTopologyConfig.RETRY_RK_RECORDED_10M;
    }

    public String retryExchange(String eventType) {
        if (RabbitTopologyConfig.RK_ANALYSIS_REQUESTED.equals(eventType)) {
            return RabbitTopologyConfig.RETRY_EXCHANGE_ANALYSIS;
        }
        return RabbitTopologyConfig.RETRY_EXCHANGE_RECORDED;
    }
}
