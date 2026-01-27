package com.yunhwan.auth.error.infra.logging;

import com.yunhwan.auth.error.domain.autherror.AuthError;
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

        // 핵심: entries(evt)가 "필드"로 들어감 (message 문자열로 안 뭉개짐)
        log.info("auth_error_event {}", entries(evt));
    }

    public void recorded(AuthError authError, Long outboxId, String idempotencyKey) {
        Map<String, Object> evt = createBaseEvent("auth_error.recorded", authError);
        evt.put("outbox_id", outboxId);
        evt.put("idempotency_key", idempotencyKey);

        evt.put("http", Map.of(
                "status", authError.getHttpStatus(),
                "method", authError.getHttpMethod(),
                "path", authError.getRequestUri()
        ));

        evt.put("error", Map.of(
                "exception", authError.getExceptionClass(),
                "message", authError.getExceptionMessage(),
                "stack_hash", authError.getStackHash()
        ));

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
}
