package com.yunhwan.auth.error.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "outbox")
public class OutboxProperties {

    private String owner;

    private Poller poller = new Poller();
    private Retry retry = new Retry();
    private Reaper reaper = new Reaper();

    @Getter @Setter
    public static class Poller {
        private int batchSize = 50;
        private long fixedDelayMs = 500;
    }

    @Getter @Setter
    public static class Retry {
        private int maxRetries = 10;
        private int delaySeconds = 60;
    }

    @Getter @Setter
    public static class Reaper {
        private int staleAfterSeconds = 300;
        private int batchSize = 100;
        private long fixedDelayMs = 5000;
    }
}
