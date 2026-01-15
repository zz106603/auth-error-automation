package com.yunhwan.auth.error.infra.support;

import com.yunhwan.auth.error.usecase.outbox.port.OwnerResolver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;

@Component
public class HostOwnerResolver implements OwnerResolver {

    @Value("${outbox.owner:}")
    private String configuredOwner;

    @Override
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
