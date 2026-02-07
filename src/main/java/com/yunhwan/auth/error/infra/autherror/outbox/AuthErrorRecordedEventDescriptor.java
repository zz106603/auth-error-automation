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
        // Outbox 멱등키는 외부 입력(requestId)이 아닌 내부 권위 식별자(authErrorId)만 사용한다.
        return "auth_error:recorded:" + payload.authErrorId();
    }
}
