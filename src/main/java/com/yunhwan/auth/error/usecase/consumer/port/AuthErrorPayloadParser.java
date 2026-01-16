package com.yunhwan.auth.error.usecase.consumer.port;

import com.yunhwan.auth.error.usecase.autherror.dto.AuthErrorRecordedPayload;

public interface AuthErrorPayloadParser {
    AuthErrorRecordedPayload parse(String payloadJson, Long outboxId);
}
