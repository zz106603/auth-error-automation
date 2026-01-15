package com.yunhwan.auth.error.infra.outbox.support;

import com.yunhwan.auth.error.usecase.outbox.port.OutboxScopeResolver;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public class NoopOutboxScopeResolver implements OutboxScopeResolver {
    
    @Override
    public void set(String prefix) { /* No-op */ }

    @Override
    public void clear() { /* No-op */ }

    @Override
    public String scopePrefixOrNull() {
        return null;
    }

}
