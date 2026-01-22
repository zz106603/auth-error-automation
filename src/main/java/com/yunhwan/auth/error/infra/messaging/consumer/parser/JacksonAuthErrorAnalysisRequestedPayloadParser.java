package com.yunhwan.auth.error.infra.messaging.consumer.parser;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yunhwan.auth.error.common.exception.NonRetryableAuthErrorException;
import com.yunhwan.auth.error.usecase.autherror.dto.AuthErrorAnalysisRequestedPayload;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class JacksonAuthErrorAnalysisRequestedPayloadParser {

    private final ObjectMapper objectMapper;

    public AuthErrorAnalysisRequestedPayload parse(String payloadJson, Long outboxId) {
        try {
            AuthErrorAnalysisRequestedPayload payload =
                    objectMapper.readValue(payloadJson, AuthErrorAnalysisRequestedPayload.class);

            if (payload.authErrorId() == null) {
                throw new NonRetryableAuthErrorException(
                        "missing authErrorId. outboxId=" + outboxId
                );
            }
            return payload;
        } catch (JsonProcessingException e) {
            throw new NonRetryableAuthErrorException(
                    "invalid payload json. outboxId=" + outboxId, e
            );
        }
    }
}
