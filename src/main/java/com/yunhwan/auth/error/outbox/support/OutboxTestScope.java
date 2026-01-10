package com.yunhwan.auth.error.outbox.support;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Profile("test")
@Component
public class OutboxTestScope {
    private static final ThreadLocal<String> PREFIX = new ThreadLocal<>();

    public void set(String prefix) { PREFIX.set(prefix); }
    public String get() { return PREFIX.get(); }
    public void clear() { PREFIX.remove(); }
}
