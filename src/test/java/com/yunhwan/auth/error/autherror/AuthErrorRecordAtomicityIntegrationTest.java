package com.yunhwan.auth.error.autherror;

import com.yunhwan.auth.error.domain.autherror.AuthError;
import com.yunhwan.auth.error.testsupport.base.AbstractIntegrationTest;
import com.yunhwan.auth.error.usecase.autherror.AuthErrorWriter;
import com.yunhwan.auth.error.usecase.autherror.dto.AuthErrorWriteCommand;
import com.yunhwan.auth.error.usecase.autherror.port.AuthErrorStore;
import com.yunhwan.auth.error.usecase.outbox.OutboxWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

@DisplayName("AuthError 기록 원자성 통합 테스트")
class AuthErrorRecordAtomicityIntegrationTest extends AbstractIntegrationTest {

    private static final String RECORDED_EVENT_TYPE = "auth.error.recorded.v1";

    @Autowired
    AuthErrorWriter authErrorWriter;

    @MockitoSpyBean
    AuthErrorStore authErrorStore;

    @MockitoSpyBean
    OutboxWriter outboxWriter;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetSpies() {
        Mockito.reset(authErrorStore, outboxWriter);
    }

    @Test
    @DisplayName("[TS-02] AuthError 저장 이전 예외 발생 시 auth_error/outbox 모두 없어야 한다")
    void 저장_이전_예외_시_auth_error_및_outbox_모두_없다() {
        String requestId = "REQ-ATOMIC-1-" + UUID.randomUUID();
        AuthErrorWriteCommand cmd = newTestCommand(requestId);

        doThrow(new RuntimeException("injected-before-save"))
                .when(authErrorStore).save(any(AuthError.class));

        assertThatThrownBy(() -> authErrorWriter.record(cmd))
                .isInstanceOf(RuntimeException.class);

        assertThat(countAuthErrorByRequestId(requestId))
                .withFailMessage("예외 발생 시 auth_error는 저장되면 안 됩니다.")
                .isEqualTo(0L);
        assertThat(countRecordedOutboxByRequestId(requestId))
                .withFailMessage("예외 발생 시 recorded outbox는 생성되면 안 됩니다.")
                .isEqualTo(0L);
    }

    @Test
    @DisplayName("[TS-02] AuthError 저장 후 Outbox 저장 이전 예외 발생 시 auth_error/outbox 모두 없어야 한다")
    void 저장_후_outbox_전_예외_시_auth_error_및_outbox_모두_없다() {
        String requestId = "REQ-ATOMIC-2-" + UUID.randomUUID();
        AuthErrorWriteCommand cmd = newTestCommand(requestId);

        doThrow(new RuntimeException("injected-before-outbox"))
                .when(outboxWriter).enqueue(any(), any(), any());

        assertThatThrownBy(() -> authErrorWriter.record(cmd))
                .isInstanceOf(RuntimeException.class);

        assertThat(countAuthErrorByRequestId(requestId))
                .withFailMessage("예외 발생 시 auth_error는 저장되면 안 됩니다.")
                .isEqualTo(0L);
        assertThat(countRecordedOutboxByRequestId(requestId))
                .withFailMessage("예외 발생 시 recorded outbox는 생성되면 안 됩니다.")
                .isEqualTo(0L);
    }

    @Test
    @DisplayName("[TS-02] Outbox 저장 후 커밋 이전 예외 발생 시 auth_error/outbox 모두 없어야 한다")
    void outbox_후_커밋_전_예외_시_auth_error_및_outbox_모두_없다() {
        String requestId = "REQ-ATOMIC-3-" + UUID.randomUUID();
        AuthErrorWriteCommand cmd = newTestCommand(requestId);

        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            throw new RuntimeException("injected-after-outbox");
        }).when(outboxWriter).enqueue(any(), any(), any());

        assertThatThrownBy(() -> authErrorWriter.record(cmd))
                .isInstanceOf(RuntimeException.class);

        assertThat(countAuthErrorByRequestId(requestId))
                .withFailMessage("예외 발생 시 auth_error는 저장되면 안 됩니다.")
                .isEqualTo(0L);
        assertThat(countRecordedOutboxByRequestId(requestId))
                .withFailMessage("예외 발생 시 recorded outbox는 생성되면 안 됩니다.")
                .isEqualTo(0L);
    }

    private AuthErrorWriteCommand newTestCommand(String requestId) {
        return new AuthErrorWriteCommand(
                requestId,
                OffsetDateTime.now(),
                401,
                "GET",
                "/api/test",
                "127.0.0.1",
                "JUnit",
                "test-user",
                "test-session",
                "IllegalStateException",
                "test exception",
                null,
                null,
                "stacktrace"
        );
    }

    private long countAuthErrorByRequestId(String requestId) {
        Long count = jdbcTemplate.queryForObject(
                "select count(*) from auth_error where request_id = ?",
                Long.class,
                requestId
        );
        return count == null ? 0L : count;
    }

    private long countRecordedOutboxByRequestId(String requestId) {
        Long count = jdbcTemplate.queryForObject(
                "select count(*) from outbox_message where event_type = ? and payload ->> 'requestId' = ?",
                Long.class,
                RECORDED_EVENT_TYPE,
                requestId
        );
        return count == null ? 0L : count;
    }
}
