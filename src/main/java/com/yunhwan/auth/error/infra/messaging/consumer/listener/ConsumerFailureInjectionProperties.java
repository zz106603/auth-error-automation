package com.yunhwan.auth.error.infra.messaging.consumer.listener;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "auth-error.loadtest.consumer-failure")
public class ConsumerFailureInjectionProperties {

    private String recordedMode = "off";
    private int recordedPercent = 0;
    private int recordedFailUntilRetryCount = 1;

    public boolean enabled() {
        return !"off".equalsIgnoreCase(recordedMode) && normalizedPercent() > 0;
    }

    public int normalizedPercent() {
        return Math.max(0, Math.min(100, recordedPercent));
    }

    public int normalizedFailUntilRetryCount() {
        return Math.max(1, recordedFailUntilRetryCount);
    }

    public String normalizedMode() {
        if (recordedMode == null || recordedMode.isBlank()) {
            return "off";
        }
        return recordedMode.trim().toLowerCase();
    }
}
