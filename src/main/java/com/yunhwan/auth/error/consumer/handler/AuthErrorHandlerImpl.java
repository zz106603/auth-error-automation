package com.yunhwan.auth.error.consumer.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yunhwan.auth.error.autherror.dto.AuthErrorRecordedPayload;
import com.yunhwan.auth.error.autherror.repository.AuthErrorRepository;
import com.yunhwan.auth.error.common.exception.NonRetryableAuthErrorException;
import com.yunhwan.auth.error.common.exception.RetryableAuthErrorException;
import com.yunhwan.auth.error.domain.auth.AuthError;
import com.yunhwan.auth.error.domain.auth.AuthErrorStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class AuthErrorHandlerImpl implements AuthErrorHandler {

    private final ObjectMapper objectMapper;
    private final AuthErrorRepository authErrorRepository;

    @Transactional
    public void handle(String payloadJson, Map<String, Object> headers) {
        Long outboxId = headerLong(headers, "outboxId");

        AuthErrorRecordedPayload payload = parse(payloadJson, outboxId);

        AuthError authError = authErrorRepository.findById(payload.authErrorId())
                .orElseThrow(() -> new NonRetryableAuthErrorException(
                        "auth_error not found. authErrorId=" + payload.authErrorId() + ", outboxId=" + outboxId
                ));

        // 이미 완료된 건이면 멱등하게 종료 (중복 소비/재전달 대비)
        if (authError.getStatus().isTerminal()) {
            log.info("[AuthErrorHandler] already done -> skip. authErrorId={}, status={}, outboxId={}",
                    authError.getId(), authError.getStatus(), outboxId);
            return;
        }

        // 처리 시작 마킹(옵션)
        authError.markProcessing("consumer"); // owner는 host/pod id로 바꿔도 됨

        try {
            // ==========================
            // TODO: 여기부터 “실제 비즈니스 처리”
            // - 예: 알림 발송, 분석 파이프라인 호출, 보안 로그 적재, 티켓 생성 등
            // ==========================

            // MVP: 일단 처리 성공으로 간주
            authError.markProcessed();

            log.info("[AuthErrorHandler] processed. authErrorId={}, outboxId={}",
                    authError.getId(), outboxId);

        } catch (IllegalArgumentException e) {
            // 재시도해도 의미 없는 케이스(입력/검증 실패 등)
            authError.markFailed("non-retryable: " + safeMsg(e));
            throw new NonRetryableAuthErrorException("non-retryable failure. outboxId=" + outboxId, e);

        } catch (Exception e) {
            // 일시 장애/외부 연동 실패 등: 재시도 대상
            authError.markRetry(nextRetryAt(authError.getRetryCount()));
            throw new RetryableAuthErrorException("retryable failure. outboxId=" + outboxId, e);
        }
    }

    private AuthErrorRecordedPayload parse(String payloadJson, Long outboxId) {
        try {
            AuthErrorRecordedPayload payload = objectMapper.readValue(payloadJson, AuthErrorRecordedPayload.class);
            if (payload.authErrorId() == null) {
                throw new NonRetryableAuthErrorException("missing authErrorId in payload. outboxId=" + outboxId);
            }
            return payload;
        } catch (Exception e) {
            throw new NonRetryableAuthErrorException("invalid payload json. outboxId=" + outboxId, e);
        }
    }

    private Long headerLong(Map<String, Object> headers, String key) {
        Object v = headers.get(key);
        if (v == null) return null;
        if (v instanceof Number n) return n.longValue();
        if (v instanceof String s) return Long.parseLong(s);
        return null;
    }

    private OffsetDateTime nextRetryAt(int retryCount) {
        // 단순 백오프(예: 10s, 30s, 60s...) - 지금은 consumer 쪽 retry TTL이 있어서 큰 의미는 없음
        int sec = switch (Math.min(retryCount, 3)) {
            case 0 -> 10;
            case 1 -> 30;
            default -> 60;
        };
        return OffsetDateTime.now().plusSeconds(sec);
    }

    private String safeMsg(Throwable t) {
        String m = t.getMessage();
        return (m == null) ? t.getClass().getSimpleName() : m;
    }
}
