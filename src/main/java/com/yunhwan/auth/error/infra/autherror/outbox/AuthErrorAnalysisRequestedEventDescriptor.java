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
        if (payload.requestId() != null && !payload.requestId().isBlank()) {
            return "auth_error:analysis_requested:" + payload.requestId();
        }
        return "auth_error:analysis_requested:" + payload.authErrorId();
    }
}
