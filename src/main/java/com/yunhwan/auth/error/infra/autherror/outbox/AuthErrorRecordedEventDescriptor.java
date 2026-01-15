package com.yunhwan.auth.error.infra.autherror.outbox;

import com.yunhwan.auth.error.domain.outbox.descriptor.OutboxEventDescriptor;
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
