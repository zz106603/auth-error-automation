package com.yunhwan.auth.error.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yunhwan.auth.error.domain.autherror.AuthError;
import com.yunhwan.auth.error.domain.autherror.AuthErrorStatus;
import com.yunhwan.auth.error.infra.messaging.rabbit.RabbitTopologyConfig;
import com.yunhwan.auth.error.testsupport.base.AbstractIntegrationTest;
import com.yunhwan.auth.error.usecase.autherror.dto.AuthErrorRecordedPayload;
import com.yunhwan.auth.error.usecase.autherror.port.AuthErrorStore;
import com.yunhwan.auth.error.usecase.consumer.handler.AuthErrorHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("[TS-05] Recorded Handler 멱등성 통합 테스트")
class RecordedHandlerIdempotencyIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    AuthErrorStore authErrorStore;

    @Autowired
    @Qualifier("authErrorRecordedHandler")
    AuthErrorHandler recordedHandler;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("[TS-05] ANALYSIS_REQUESTED 상태에서 recorded 재전달 시 analysis_requested outbox는 생성되지 않는다")
    void analysis_requested_상태에서_recorded_재전달_시_outbox_미생성() throws Exception {
        AuthError authError = createAuthError(AuthErrorStatus.ANALYSIS_REQUESTED);

        long before = countAnalysisRequestedOutbox(authError.getId());

        AuthErrorRecordedPayload payload = new AuthErrorRecordedPayload(
                authError.getId(),
                authError.getRequestId(),
                authError.getOccurredAt()
        );
        String payloadJson = objectMapper.writeValueAsString(payload);

        recordedHandler.handle(payloadJson, headers(1001L, RabbitTopologyConfig.RK_RECORDED, "auth_error"));

        long after = countAnalysisRequestedOutbox(authError.getId());

        AuthError reloaded = authErrorStore.findById(authError.getId()).orElseThrow();

        assertThat(after)
                .withFailMessage("analysis_requested outbox는 추가 생성되면 안 됩니다.")
                .isEqualTo(before);
        assertThat(reloaded.getStatus())
                .withFailMessage("AuthError 상태는 ANALYSIS_REQUESTED 그대로여야 합니다.")
                .isEqualTo(AuthErrorStatus.ANALYSIS_REQUESTED);
    }

    private AuthError createAuthError(AuthErrorStatus status) {
        String requestId = "REQ-TS05-" + UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        AuthError authError = AuthError.record(requestId, now, now, "test", "test");
        if (status == AuthErrorStatus.ANALYSIS_REQUESTED) {
            authError.markAnalysisRequested();
        }
        return authErrorStore.save(authError);
    }

    private Map<String, Object> headers(long outboxId, String eventType, String aggregateType) {
        Map<String, Object> headers = new HashMap<>();
        headers.put("outboxId", outboxId);
        headers.put("eventType", eventType);
        headers.put("aggregateType", aggregateType);
        return headers;
    }

    private long countAnalysisRequestedOutbox(Long authErrorId) {
        String idempotencyKey = "auth_error:analysis_requested:" + authErrorId;
        Long count = jdbcTemplate.queryForObject(
                "select count(*) from outbox_message where idempotency_key = ?",
                Long.class,
                idempotencyKey
        );
        return count == null ? 0L : count;
    }
}
