package com.yunhwan.auth.error.usecase.consumer.port;

import com.yunhwan.auth.error.usecase.autherror.dto.AuthErrorAnalysisRequestedPayload;

public interface AuthErrorAnalysisRequestedPayloadParser {
    AuthErrorAnalysisRequestedPayload parse(String payloadJson, Long outboxId);
}
