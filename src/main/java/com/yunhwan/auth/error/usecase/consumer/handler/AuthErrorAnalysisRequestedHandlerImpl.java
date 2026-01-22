package com.yunhwan.auth.error.usecase.consumer.handler;

import com.yunhwan.auth.error.common.exception.NonRetryableAuthErrorException;
import com.yunhwan.auth.error.domain.autherror.AuthError;
import com.yunhwan.auth.error.domain.autherror.AuthErrorStatus;
import com.yunhwan.auth.error.infra.messaging.consumer.parser.JacksonAuthErrorAnalysisRequestedPayloadParser;
import com.yunhwan.auth.error.usecase.autherror.dto.AuthErrorAnalysisRequestedPayload;
import com.yunhwan.auth.error.usecase.autherror.port.AuthErrorStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.Map;

@Slf4j
@Component("authErrorAnalysisRequestedHandler")
@RequiredArgsConstructor
public class AuthErrorAnalysisRequestedHandlerImpl implements AuthErrorHandler{

    private final AuthErrorStore authErrorStore;
    private final JacksonAuthErrorAnalysisRequestedPayloadParser parser;

    @Override
    @Transactional
    public void handle(String payload, Map<String, Object> headers) {

        Long outboxId = requireLong(headers, "outboxId");

        requireString(headers, "eventType");
        requireString(headers, "aggregateType");

        AuthErrorAnalysisRequestedPayload parsed = parser.parse(payload, outboxId);

        AuthError authError = authErrorStore.findById(parsed.authErrorId())
                .orElseThrow(() ->
                        new NonRetryableAuthErrorException(
                                "authError not found id=" + parsed.authErrorId()));

        if (EnumSet.of(AuthErrorStatus.PROCESSED, AuthErrorStatus.RESOLVED, AuthErrorStatus.IGNORED)
                .contains(authError.getStatus())) {
            return;
        }

        authError.markProcessed();
    }

    private Long requireLong(Map<String, Object> headers, String key) {
        Object v = headers.get(key);
        if (v == null) {
            throw new NonRetryableAuthErrorException("missing header: " + key);
        }
        try {
            if (v instanceof Number n) return n.longValue();
            return Long.parseLong(String.valueOf(v));
        } catch (Exception e) {
            throw new NonRetryableAuthErrorException("invalid header(long): " + key + "=" + v, e);
        }
    }

    private String requireString(Map<String, Object> headers, String key) {
        Object v = headers.get(key);
        if (v == null) {
            throw new NonRetryableAuthErrorException("missing header: " + key);
        }
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) {
            throw new NonRetryableAuthErrorException("blank header: " + key);
        }
        return s;
    }
}
