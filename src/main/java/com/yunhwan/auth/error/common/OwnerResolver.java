package com.yunhwan.auth.error.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;

@Component
public class OwnerResolver {

    @Value("${outbox.owner:}")
    private String configuredOwner;

    public String resolve() {
        // 1) 설정값이 있으면 최우선
        if (configuredOwner != null && !configuredOwner.isBlank()) {
            return configuredOwner;
        }

        // 2) 호스트명 fallback
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            // 3) 최후 fallback
            return "unknown";
        }
    }
}
