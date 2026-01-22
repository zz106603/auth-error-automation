package com.yunhwan.auth.error.infra.autherror.outbox;

import com.yunhwan.auth.error.domain.outbox.descriptor.OutboxEventDescriptor;
import com.yunhwan.auth.error.usecase.autherror.dto.AuthErrorRecordedPayload;
import org.springframework.stereotype.Component;

@Component
public class AuthErrorRecordedEventDescriptor implements OutboxEventDescriptor<AuthErrorRecordedPayload> {

    @Override
    public String aggregateType() {
        return "auth_error";
    }

    @Override
    public String eventType() {
        return "auth.error.recorded.v1";
    }

    @Override
    public String idempotencyKey(AuthErrorRecordedPayload payload) {
        if (payload.requestId() != null && !payload.requestId().isBlank()) {
            return "auth_error:recorded:" + payload.requestId();
        }
        return "auth_error:recorded:" + payload.authErrorId();
    }
}
