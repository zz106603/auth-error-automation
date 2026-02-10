package com.yunhwan.auth.error.decision;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yunhwan.auth.error.common.exception.RetryableAuthErrorException;
import com.yunhwan.auth.error.domain.autherror.AuthError;
import com.yunhwan.auth.error.domain.autherror.AuthErrorStatus;
import com.yunhwan.auth.error.infra.messaging.rabbit.RabbitTopologyConfig;
import com.yunhwan.auth.error.testsupport.base.AbstractIntegrationTest;
import com.yunhwan.auth.error.usecase.autherror.dto.AuthErrorRecordedPayload;
import com.yunhwan.auth.error.usecase.autherror.port.AuthErrorStore;
import com.yunhwan.auth.error.usecase.consumer.handler.AuthErrorHandler;
import com.yunhwan.auth.error.usecase.outbox.OutboxWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

@DisplayName("[TS-07] Analysis 요청 원자성 통합 테스트")
class DecisionAtomicityIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    AuthErrorStore authErrorStore;

    @Autowired
    @Qualifier("authErrorRecordedHandler")
    AuthErrorHandler recordedHandler;

    @Autowired
    ObjectMapper objectMapper;

    @MockitoSpyBean
    OutboxWriter outboxWriter;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetSpy() {
        Mockito.reset(outboxWriter);
    }

    @Test
    @DisplayName("[TS-07] outbox enqueue 이전 예외 발생 시 ANALYSIS_REQUESTED/outbox 모두 남지 않는다")
    void outbox_이전_예외_시_상태_및_outbox_모두_없다() throws Exception {
        AuthError authError = createAuthError(AuthErrorStatus.NEW);

        doThrow(new RuntimeException("injected-before-outbox"))
                .when(outboxWriter).enqueue(any(), any(), any());

        AuthErrorRecordedPayload payload = new AuthErrorRecordedPayload(
                authError.getId(),
                authError.getRequestId(),
                authError.getOccurredAt()
        );
        String payloadJson = objectMapper.writeValueAsString(payload);

        assertThatThrownBy(() -> recordedHandler.handle(
                payloadJson,
                headers(2001L, RabbitTopologyConfig.RK_RECORDED, "auth_error")
        )).isInstanceOf(RetryableAuthErrorException.class);

        AuthError reloaded = authErrorStore.findById(authError.getId()).orElseThrow();

        assertThat(reloaded.getStatus())
                .withFailMessage("예외 발생 시 ANALYSIS_REQUESTED 상태가 남으면 안 됩니다.")
                .isEqualTo(AuthErrorStatus.NEW);
        assertThat(countAnalysisRequestedOutbox(authError.getId()))
                .withFailMessage("예외 발생 시 analysis_requested outbox가 생성되면 안 됩니다.")
                .isEqualTo(0L);
    }

    @Test
    @DisplayName("[TS-07] outbox enqueue 후 예외 발생 시 status/outbox 모두 롤백된다")
    void outbox_이후_예외_시_상태_및_outbox_모두_롤백된다() throws Exception {
        AuthError authError = createAuthError(AuthErrorStatus.NEW);

        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            throw new RuntimeException("injected-after-outbox");
        }).when(outboxWriter).enqueue(any(), any(), any());

        AuthErrorRecordedPayload payload = new AuthErrorRecordedPayload(
                authError.getId(),
                authError.getRequestId(),
                authError.getOccurredAt()
        );
        String payloadJson = objectMapper.writeValueAsString(payload);

        assertThatThrownBy(() -> recordedHandler.handle(
                payloadJson,
                headers(2002L, RabbitTopologyConfig.RK_RECORDED, "auth_error")
        )).isInstanceOf(RetryableAuthErrorException.class);

        AuthError reloaded = authErrorStore.findById(authError.getId()).orElseThrow();

        assertThat(reloaded.getStatus())
                .withFailMessage("예외 발생 시 ANALYSIS_REQUESTED 상태가 남으면 안 됩니다.")
                .isEqualTo(AuthErrorStatus.NEW);
        assertThat(countAnalysisRequestedOutbox(authError.getId()))
                .withFailMessage("예외 발생 시 analysis_requested outbox가 남으면 안 됩니다.")
                .isEqualTo(0L);
    }

    private AuthError createAuthError(AuthErrorStatus status) {
        String requestId = "REQ-TS07-" + UUID.randomUUID();
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
