package com.yunhwan.auth.error.usecase.consumer.handler;

import com.yunhwan.auth.error.common.exception.NonRetryableAuthErrorException;
import com.yunhwan.auth.error.common.exception.RetryableAuthErrorException;
import com.yunhwan.auth.error.domain.autherror.AuthError;
import com.yunhwan.auth.error.domain.autherror.AuthErrorStatus;
import com.yunhwan.auth.error.domain.outbox.OutboxMessage;
import com.yunhwan.auth.error.domain.outbox.descriptor.OutboxEventDescriptor;
import com.yunhwan.auth.error.usecase.autherror.dto.AuthErrorAnalysisRequestedPayload;
import com.yunhwan.auth.error.usecase.autherror.dto.AuthErrorRecordedPayload;
import com.yunhwan.auth.error.usecase.autherror.port.AuthErrorStore;
import com.yunhwan.auth.error.usecase.consumer.port.AuthErrorPayloadParser;
import com.yunhwan.auth.error.usecase.outbox.OutboxWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Map;

@Slf4j
@Component("authErrorRecordedHandler")
@RequiredArgsConstructor
public class AuthErrorRecordHandlerImpl implements AuthErrorHandler {

    private final AuthErrorStore authErrorStore;
    private final AuthErrorPayloadParser payloadParser;
    private final OutboxWriter outboxWriter;
    private final Clock clock;
    private final OutboxEventDescriptor<AuthErrorAnalysisRequestedPayload> analysisRequestedDescriptor;

    @Override
    @Transactional
    public void handle(String payloadJson, Map<String, Object> headers) {

        // 0) 입력 계약(필수 헤더) - Handler 단독 호출에도 안전해야 함
        Long outboxId = requireLong(headers, "outboxId");

        // eventType/aggregateType는 지금 당장 handler에서 안 써도,
        // "메시지 형식 계약"으로 강제해두면 운영/디버깅에 도움 됨.
        requireString(headers, "eventType");
        requireString(headers, "aggregateType");

        // 1) payload 파싱 실패는 재시도 의미 없음
        final AuthErrorRecordedPayload payload;
        try {
            payload = payloadParser.parse(payloadJson, outboxId);
        } catch (Exception e) {
            throw new NonRetryableAuthErrorException("invalid payload. outboxId=" + outboxId, e);
        }

        // 2) 대상 도메인 없으면 재시도 의미 없음
        AuthError authError = authErrorStore.findById(payload.authErrorId())
                .orElseThrow(() -> new NonRetryableAuthErrorException(
                        "auth_error not found. authErrorId=" + payload.authErrorId() + ", outboxId=" + outboxId
                ));

        // 3) 멱등 가드: terminal 또는 이미 analysis 요청한 건 예외 없이 종료(=성공 취급)
        AuthErrorStatus status = authError.getStatus();
        if (status != null && (status.isTerminal() || status == AuthErrorStatus.ANALYSIS_REQUESTED)) {
            log.info("[AuthErrorHandler] already requested/terminal -> skip. authErrorId={}, status={}, outboxId={}",
                    authError.getId(), status, outboxId);
            return;
        }

        // 4) 비즈니스 처리
        try {
            // ==========================
            // TODO: 실제 비즈니스 처리
            // ==========================

            OffsetDateTime now = OffsetDateTime.now(clock);

            AuthErrorAnalysisRequestedPayload analysisPayload = new AuthErrorAnalysisRequestedPayload(
                    payload.authErrorId(),
                    payload.requestId(),
                    payload.occurredAt(),
                    now
            );

            OutboxMessage outbox = outboxWriter.enqueue(
                    analysisRequestedDescriptor,
                    String.valueOf(payload.authErrorId()), // aggregateId
                    analysisPayload
            );

            // 요청 상태
            authError.markAnalysisRequested();

            log.info("[AuthErrorHandler] analysis requested. authErrorId={}, outboxId={}, analysisOutboxId={}",
                    authError.getId(), outboxId, outbox.getId());

        } catch (IllegalArgumentException e) {
            // 입력/검증 실패: 재시도 의미 없음
            authError.markFailed("non-retryable: " + safeMsg(e));
            throw new NonRetryableAuthErrorException("non-retryable failure. outboxId=" + outboxId, e);

        } catch (NonRetryableAuthErrorException | RetryableAuthErrorException e) {
            // 이미 분류된 예외는 그대로 전달
            throw e;

        } catch (Exception e) {
            // 외부 연동/일시 장애 등: 재시도 대상
            authError.markRetry(); // 시간 없이 상태만
            throw new RetryableAuthErrorException("retryable failure. outboxId=" + outboxId, e);
        }
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

    private String safeMsg(Throwable t) {
        String m = t.getMessage();
        return (m == null) ? t.getClass().getSimpleName() : m;
    }
}
