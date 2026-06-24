package com.yunhwan.auth.error.usecase.autherror.port;

import com.yunhwan.auth.error.domain.autherror.AuthError;
import com.yunhwan.auth.error.domain.autherror.AuthErrorStatus;
import com.yunhwan.auth.error.domain.autherror.analysis.AuthErrorAnalysisResult;

public interface AuthErrorEventPublisher {

    void analysisCompleted(AuthError authError, AuthErrorAnalysisResult result);

    void recorded(AuthError authError, Long outboxId, String idempotencyKey);

    void decisionApplied(
            AuthError authError,
            AuthErrorStatus fromStatus,
            AuthErrorStatus toStatus,
            String decisionType,
            String decidedBy,
            String note
    );
}
