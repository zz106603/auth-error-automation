package com.yunhwan.auth.error.outbox.support.impl;

import com.yunhwan.auth.error.outbox.support.api.OutboxEventDescriptor;
import org.springframework.stereotype.Component;

@Component
public class AuthErrorRecordedEventDescriptor implements OutboxEventDescriptor {

    @Override
    public String aggregateType() {
        return "AUTH_ERROR";
    }

    @Override
    public String eventType() {
        return "AUTH_ERROR_RECORDED";
    }
}
