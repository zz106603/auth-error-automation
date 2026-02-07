package com.yunhwan.auth.error.infra.autherror.outbox;

import com.yunhwan.auth.error.domain.outbox.descriptor.OutboxEventDescriptor;
import com.yunhwan.auth.error.usecase.autherror.dto.AuthErrorAnalysisRequestedPayload;
import org.springframework.stereotype.Component;

@Component
public class AuthErrorAnalysisRequestedEventDescriptor implements OutboxEventDescriptor<AuthErrorAnalysisRequestedPayload> {

    @Override
    public String aggregateType() {
        return "auth_error";
    }

    @Override
    public String eventType() {
        return "auth.error.analysis.requested.v1";
    }

    @Override
    public String idempotencyKey(AuthErrorAnalysisRequestedPayload payload) {
        // Outbox 멱등키는 외부 입력(requestId)이 아닌 내부 권위 식별자(authErrorId)만 사용한다.
        return "auth_error:analysis_requested:" + payload.authErrorId();
    }
}
