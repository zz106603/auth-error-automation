package com.yunhwan.auth.error.infra.messaging.consumer.parser;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yunhwan.auth.error.common.exception.NonRetryableAuthErrorException;
import com.yunhwan.auth.error.usecase.autherror.dto.AuthErrorRecordedPayload;
import com.yunhwan.auth.error.usecase.consumer.port.AuthErrorPayloadParser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class JacksonAuthErrorPayloadParser implements AuthErrorPayloadParser {

    private final ObjectMapper objectMapper;

    @Override
    public AuthErrorRecordedPayload parse(String payloadJson, Long outboxId) {
        try {
            AuthErrorRecordedPayload payload =
                    objectMapper.readValue(payloadJson, AuthErrorRecordedPayload.class);
            if (payload.authErrorId() == null) {
                throw new NonRetryableAuthErrorException(
                        "missing authErrorId. outboxId=" + outboxId
                );
            }
            return payload;
        } catch (Exception e) {
            throw new NonRetryableAuthErrorException(
                    "invalid payload json. outboxId=" + outboxId, e
            );
        }
    }
}
