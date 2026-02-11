package com.yunhwan.auth.error.infra.messaging.rabbit;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "auth-error.rabbit.retry")
public class RabbitRetryProperties {

    /**
     * retry queue TTL (ms)
     */
    private int ttlShort = 10_000;
    private int ttlMedium  = 60_000;
    private int ttlLong = 600_000;

    /**
     * ladder 기준
     * 1~fastMax  -> 10s
     * fastMax+1 ~ mediumMax -> 1m
     * mediumMax+1 ~ -> 10m
     */
    private int fastMax = 3;
    private int mediumMax = 6;

}
