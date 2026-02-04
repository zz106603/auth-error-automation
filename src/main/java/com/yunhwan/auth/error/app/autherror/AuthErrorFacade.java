package com.yunhwan.auth.error.app.autherror;


import com.yunhwan.auth.error.app.api.auth.dto.AuthErrorRecordRequest;
import com.yunhwan.auth.error.app.api.auth.dto.AuthErrorRecordResponse;
import com.yunhwan.auth.error.domain.autherror.AuthError;
import com.yunhwan.auth.error.usecase.autherror.AuthErrorWriter;
import com.yunhwan.auth.error.usecase.autherror.dto.AuthErrorWriteCommand;
import com.yunhwan.auth.error.usecase.autherror.dto.AuthErrorWriteResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthErrorFacade {

    /**
     * 페이로드 크기 제한 : k6 부하에서 DB/Outbox/MQ 병목이 "메시지 크기"로 왜곡되는 걸 방지.
     */
    private static final int MAX_STACKTRACE_CHARS = 8_000;

    /**
     * 예외 메시지 제한 : 메시지가 과도하게 길거나 민감정보가 섞여 들어오는 경우를 대비.
     */
    private static final int MAX_EXCEPTION_MESSAGE_CHARS = 1_000;

    private static final int SAMPLE_DENOMINATOR = 100; // 100건 중 1건

    private final AuthErrorWriter authErrorWriter;

    public AuthErrorRecordResponse record(AuthErrorRecordRequest req) {
        int msgBeforeLen = safeLen(req.exceptionMessage());
        int stackBeforeLen = safeLen(req.stacktrace());

        // "수집 정책"은 여기서 적용해서, API payload 변경이 엔티티/도메인까지 번지는 걸 막는다.
        String exceptionMessage = sanitizeAndTruncate(req.exceptionMessage(), MAX_EXCEPTION_MESSAGE_CHARS);
        String rootCauseMessage = sanitizeAndTruncate(req.rootCauseMessage(), MAX_EXCEPTION_MESSAGE_CHARS);

        // stacktrace는 용량 폭발의 주범이라 반드시 상한을 둔다.
        String stacktrace = sanitizeAndTruncate(req.stacktrace(), MAX_STACKTRACE_CHARS);

        int msgAfterLen = safeLen(exceptionMessage);
        int stackAfterLen = safeLen(stacktrace);

        logPayloadLenSampleIfNeeded(req.requestId(), req.exceptionClass(), msgBeforeLen, msgAfterLen, stackBeforeLen, stackAfterLen);

        AuthErrorWriteCommand cmd = new AuthErrorWriteCommand(
                req.requestId(),
                req.occurredAt(),

                req.httpStatus(),

                req.httpMethod(),
                req.requestUri(),
                req.clientIp(),
                req.userAgent(),
                req.userId(),
                req.sessionId(),

                req.exceptionClass(),
                exceptionMessage,
                req.rootCauseClass(),
                rootCauseMessage,
                stacktrace
        );
        AuthErrorWriteResult result = authErrorWriter.record(cmd);
        return new AuthErrorRecordResponse(result.authErrorId(), result.outboxId());
    }

    private void logPayloadLenSampleIfNeeded(String requestId,
                                             String exceptionClass,
                                             int msgBefore,
                                             int msgAfter,
                                             int stackBefore,
                                             int stackAfter) {
        if (ThreadLocalRandom.current().nextInt(SAMPLE_DENOMINATOR) != 0) {
            return;
        }
        try {
            log.info("auth_error_payload_sample requestId={}, exceptionClass={}, messageLenBefore={}, messageLenAfter={}, stackLenBefore={}, stackLenAfter={}",
                    requestId, exceptionClass, msgBefore, msgAfter, stackBefore, stackAfter);
        } catch (Exception e) {
            // 로깅 때문에 파이프라인이 깨지면 안 됨
            log.debug("auth_error_payload_sample logging failed: {}", e.toString());
        }
    }

    private static int safeLen(String s) {
        return s == null ? 0 : s.length();
    }

    /**
     *  (KR) 줄바꿈 정규화 후 최대 길이로 절단.
     */
    private static String sanitizeAndTruncate(String input, int maxChars) {
        if (input == null) {
            return null;
        }

        String s = normalizeLineEndings(input).trim();
        if (s.isEmpty()) {
            return null;
        }

        if (maxChars <= 0) {
            // Defensive: if misconfigured, do not store the payload.
            return null;
        }

        if (s.length() <= maxChars) {
            return s;
        }

        return s.substring(0, maxChars);
    }

    /**
     *  CRLF/CR을 LF로 통일해서 저장/해시/집계 안정성을 높인다.
     */
    private static String normalizeLineEndings(String s) {
        // Replace CRLF first, then remaining CR.
        return s.replace("\r\n", "\n").replace("\r", "\n");
    }
}
