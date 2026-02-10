package com.yunhwan.auth.error.state;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yunhwan.auth.error.domain.autherror.AuthError;
import com.yunhwan.auth.error.domain.autherror.AuthErrorStatus;
import com.yunhwan.auth.error.infra.messaging.rabbit.RabbitTopologyConfig;
import com.yunhwan.auth.error.testsupport.base.AbstractIntegrationTest;
import com.yunhwan.auth.error.usecase.autherror.dto.AuthErrorAnalysisRequestedPayload;
import com.yunhwan.auth.error.usecase.autherror.dto.AuthErrorRecordedPayload;
import com.yunhwan.auth.error.usecase.autherror.port.AuthErrorStore;
import com.yunhwan.auth.error.usecase.consumer.handler.AuthErrorHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("[TS-13] Terminal 상태 보호 통합 테스트")
class TerminalStateSkipIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    AuthErrorStore authErrorStore;

    @Autowired
    @Qualifier("authErrorRecordedHandler")
    AuthErrorHandler recordedHandler;

    @Autowired
    @Qualifier("authErrorAnalysisRequestedHandler")
    AuthErrorHandler analysisRequestedHandler;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @ParameterizedTest
    @EnumSource(value = AuthErrorStatus.class, names = {"PROCESSED", "FAILED", "RESOLVED", "IGNORED"})
    @DisplayName("[TS-13] terminal 상태에서는 recorded 이벤트를 무시한다")
    void terminal_상태에서는_recorded_이벤트를_무시한다(AuthErrorStatus terminalStatus) throws Exception {
        AuthError authError = createAuthError(terminalStatus);

        long beforeOutbox = countAnalysisRequestedOutbox(authError.getId());

        AuthErrorRecordedPayload payload = new AuthErrorRecordedPayload(
                authError.getId(),
                authError.getRequestId(),
                authError.getOccurredAt()
        );
        String payloadJson = objectMapper.writeValueAsString(payload);

        recordedHandler.handle(payloadJson, headers(3001L, RabbitTopologyConfig.RK_RECORDED, "auth_error"));

        AuthError reloaded = authErrorStore.findById(authError.getId()).orElseThrow();
        long afterOutbox = countAnalysisRequestedOutbox(authError.getId());

        assertThat(reloaded.getStatus())
                .withFailMessage("terminal 상태는 변경되면 안 됩니다.")
                .isEqualTo(terminalStatus);
        assertThat(afterOutbox)
                .withFailMessage("terminal 상태에서는 analysis_requested outbox가 생성되면 안 됩니다.")
                .isEqualTo(beforeOutbox);
    }

    @ParameterizedTest
    @EnumSource(value = AuthErrorStatus.class, names = {"PROCESSED", "FAILED", "RESOLVED", "IGNORED"})
    @DisplayName("[TS-13] terminal 상태에서는 analysis_requested 이벤트를 무시한다")
    void terminal_상태에서는_analysis_requested_이벤트를_무시한다(AuthErrorStatus terminalStatus) throws Exception {
        AuthError authError = createAuthError(terminalStatus);

        long beforeResults = countAnalysisResult(authError.getId());
        long beforeClusterItems = countClusterItems(authError.getId());
        long beforeOutbox = countOutboxByAggregateId(authError.getId());

        AuthErrorAnalysisRequestedPayload payload = new AuthErrorAnalysisRequestedPayload(
                authError.getId(),
                authError.getRequestId(),
                authError.getOccurredAt(),
                OffsetDateTime.now()
        );
        String payloadJson = objectMapper.writeValueAsString(payload);

        analysisRequestedHandler.handle(payloadJson, headers(3002L, RabbitTopologyConfig.RK_ANALYSIS_REQUESTED, "auth_error"));

        AuthError reloaded = authErrorStore.findById(authError.getId()).orElseThrow();
        long afterResults = countAnalysisResult(authError.getId());
        long afterClusterItems = countClusterItems(authError.getId());
        long afterOutbox = countOutboxByAggregateId(authError.getId());

        assertThat(reloaded.getStatus())
                .withFailMessage("terminal 상태는 변경되면 안 됩니다.")
                .isEqualTo(terminalStatus);
        assertThat(afterResults)
                .withFailMessage("terminal 상태에서는 analysis 결과가 생성되면 안 됩니다.")
                .isEqualTo(beforeResults);
        assertThat(afterClusterItems)
                .withFailMessage("terminal 상태에서는 cluster link가 생성되면 안 됩니다.")
                .isEqualTo(beforeClusterItems);
        assertThat(afterOutbox)
                .withFailMessage("terminal 상태에서는 outbox 변경이 발생하면 안 됩니다.")
                .isEqualTo(beforeOutbox);
    }

    private AuthError createAuthError(AuthErrorStatus status) {
        String requestId = "REQ-TS13-" + UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        AuthError authError = AuthError.record(requestId, now, now, "test", "test");
        switch (status) {
            case PROCESSED -> authError.markProcessed("done");
            case FAILED -> authError.markFailed("failed");
            case RESOLVED -> authError.resolve("resolved");
            case IGNORED -> authError.markIgnored("ignored");
            default -> {
            }
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

    private long countOutboxByAggregateId(Long authErrorId) {
        Long count = jdbcTemplate.queryForObject(
                "select count(*) from outbox_message where aggregate_id = ?",
                Long.class,
                String.valueOf(authErrorId)
        );
        return count == null ? 0L : count;
    }

    private long countAnalysisResult(Long authErrorId) {
        Long count = jdbcTemplate.queryForObject(
                "select count(*) from auth_error_analysis_result where auth_error_id = ?",
                Long.class,
                authErrorId
        );
        return count == null ? 0L : count;
    }

    private long countClusterItems(Long authErrorId) {
        Long count = jdbcTemplate.queryForObject(
                "select count(*) from auth_error_cluster_item where auth_error_id = ?",
                Long.class,
                authErrorId
        );
        return count == null ? 0L : count;
    }
}
