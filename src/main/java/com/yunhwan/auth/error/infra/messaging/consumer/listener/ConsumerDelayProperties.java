package com.yunhwan.auth.error.infra.messaging.consumer.listener;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "auth-error.loadtest.consumer-delay")
public class ConsumerDelayProperties {

    private long recordedMs = 0;
}
