package com.yunhwan.auth.error.autherror;

import com.yunhwan.auth.error.testsupport.base.AbstractIntegrationTest;
import com.yunhwan.auth.error.usecase.autherror.AuthErrorWriter;
import com.yunhwan.auth.error.usecase.autherror.dto.AuthErrorWriteCommand;
import com.yunhwan.auth.error.usecase.autherror.dto.AuthErrorWriteResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("[TS-01] AuthError API 멱등성 통합 테스트")
class AuthErrorIdempotencyIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    AuthErrorWriter authErrorWriter;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("[TS-01] 동일 requestId 중복 기록 시 auth_error 1건만 생성되고 동일한 결과를 반환한다")
    void 동일_requestId_중복_기록_시_auth_error_1건만_생성된다() {
        String requestId = "REQ-IDEMP-" + UUID.randomUUID();
        AuthErrorWriteCommand cmd = newTestCommand(requestId);

        AuthErrorWriteResult first = authErrorWriter.record(cmd);
        AuthErrorWriteResult second = authErrorWriter.record(cmd);

        assertThat(second.authErrorId())
                .withFailMessage("중복 요청 시 동일한 authErrorId가 반환되어야 합니다.")
                .isEqualTo(first.authErrorId());
        assertThat(second.outboxId())
                .withFailMessage("중복 요청 시 동일한 outboxId가 반환되어야 합니다.")
                .isEqualTo(first.outboxId());

        assertThat(countAuthErrorByRequestId(requestId))
                .withFailMessage("requestId 기준 auth_error는 1건만 존재해야 합니다.")
                .isEqualTo(1L);
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
}
