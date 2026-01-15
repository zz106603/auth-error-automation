package com.yunhwan.auth.error.infra.outbox.support;

import com.yunhwan.auth.error.usecase.outbox.port.OutboxScopeResolver;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Profile("test")
@Component
public class TestOutboxScopeResolver implements OutboxScopeResolver {
    private static final ThreadLocal<String> PREFIX = new ThreadLocal<>();

    @Override
    public void set(String prefix) { PREFIX.set(prefix); }

    @Override
    public void clear() { PREFIX.remove(); }

    @Override
    public String scopePrefixOrNull() { return PREFIX.get(); }
}
