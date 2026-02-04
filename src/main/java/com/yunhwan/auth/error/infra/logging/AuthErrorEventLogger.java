package com.yunhwan.auth.error.infra.logging;

import com.yunhwan.auth.error.domain.autherror.AuthError;
import com.yunhwan.auth.error.domain.autherror.AuthErrorStatus;
import com.yunhwan.auth.error.domain.autherror.analysis.AuthErrorAnalysisResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static net.logstash.logback.argument.StructuredArguments.entries;

@Slf4j
@Component
@RequiredArgsConstructor
public class AuthErrorEventLogger {

    private final Clock clock;

    public void analysisCompleted(AuthError authError, AuthErrorAnalysisResult r) {
        Map<String, Object> evt = createBaseEvent("auth_error.analysis_completed", authError);

        Map<String, Object> analysis = new LinkedHashMap<>();
        analysis.put("category", r.getCategory());
        analysis.put("severity", r.getSeverity());
        analysis.put("confidence", r.getConfidence());
        analysis.put("reason", r.getSummary());
        analysis.put("model_version", r.getModel());
        analysis.put("prompt_version", r.getAnalysisVersion());

        evt.put("analysis", analysis);

        log.info("auth_error_event {}", entries(evt));
    }

    public void recorded(AuthError authError, Long outboxId, String idempotencyKey) {
        Map<String, Object> evt = createBaseEvent("auth_error.recorded", authError);
        evt.put("outbox_id", outboxId);
        evt.put("idempotency_key", idempotencyKey);

        // Map.of는 null 허용 X -> null-safe map builder로 교체
        evt.put("http", mapOfNonNull(
                "status", authError.getHttpStatus(),
                "method", authError.getHttpMethod(),
                "path", authError.getRequestUri()
        ));

        evt.put("error", mapOfNonNull(
                "exception", authError.getExceptionClass(),
                "message", authError.getExceptionMessage(),
                "stack_hash", authError.getStackHash()
        ));

        log.info("auth_error_event {}", entries(evt));
    }

    public void decisionApplied(
            AuthError authError,
            AuthErrorStatus fromStatus,
            AuthErrorStatus toStatus,
            String decisionType,
            String decidedBy,
            String note
    ) {
        Map<String, Object> evt = createBaseEvent("auth_error.decision_applied", authError);

        evt.put("from_status", String.valueOf(fromStatus));
        evt.put("to_status", String.valueOf(toStatus));

        Map<String, Object> decision = new LinkedHashMap<>();
        decision.put("type", decisionType);
        decision.put("decided_by", decidedBy);
        if (note != null && !note.isBlank()) decision.put("note", note);

        evt.put("decision", decision);

        log.info("auth_error_event {}", entries(evt));
    }

    private Map<String, Object> createBaseEvent(String eventType, AuthError authError) {
        Map<String, Object> evt = new LinkedHashMap<>();
        evt.put("event_type", eventType);
        evt.put("event_id", UUID.randomUUID().toString());
        evt.put("occurred_at", OffsetDateTime.now(clock).toString());
        evt.put("auth_error_id", authError.getId());
        return evt;
    }

    /**
     * EN: Null-safe map builder (skips null values).
     * KR: Map.of 대체 - null 값은 아예 put하지 않아 NPE를 원천 차단.
     */
    private Map<String, Object> mapOfNonNull(Object... kv) {
        if (kv.length % 2 != 0) {
            throw new IllegalArgumentException("kv length must be even. length=" + kv.length);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            Object k = kv[i];
            Object v = kv[i + 1];
            if (k == null) {
                throw new IllegalArgumentException("key must not be null");
            }
            if (v != null) {
                m.put(String.valueOf(k), v);
            }
        }
        return m;
    }
}
