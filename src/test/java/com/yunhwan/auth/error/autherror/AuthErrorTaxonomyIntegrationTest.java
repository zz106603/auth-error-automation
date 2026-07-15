package com.yunhwan.auth.error.autherror;

import com.yunhwan.auth.error.testsupport.base.AbstractIntegrationTest;
import com.yunhwan.auth.error.usecase.autherror.AuthErrorWriter;
import com.yunhwan.auth.error.usecase.autherror.dto.AuthErrorWriteCommand;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("[13] AuthError taxonomy 입력 모델 통합 테스트")
class AuthErrorTaxonomyIntegrationTest extends AbstractIntegrationTest {

    private static final String PRINCIPAL_HASH =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String IP_HASH =
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    @Autowired
    AuthErrorWriter authErrorWriter;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("taxonomy context는 auth_error에 구조화되어 저장된다")
    void taxonomy_context는_auth_error에_저장된다() {
        String requestId = "REQ-TAXONOMY-" + UUID.randomUUID();

        authErrorWriter.record(newCommand(
                requestId,
                "TOKEN_INVALID_SIGNATURE",
                "INTERNAL_AUTH",
                "WEB",
                "/api/login",
                PRINCIPAL_HASH,
                IP_HASH,
                "CHROME"
        ));

        Map<String, Object> row = jdbcTemplate.queryForMap("""
                select error_type,
                       auth_failure_severity,
                       auth_failure_retryable,
                       auth_failure_security_signal,
                       provider,
                       client_type,
                       endpoint,
                       principal_hash,
                       ip_hash,
                       user_agent_family
                  from auth_error
                 where request_id = ?
                """, requestId);

        assertThat(row.get("error_type")).isEqualTo("TOKEN_INVALID_SIGNATURE");
        assertThat(row.get("auth_failure_severity")).isEqualTo("HIGH");
        assertThat(row.get("auth_failure_retryable")).isEqualTo(false);
        assertThat(row.get("auth_failure_security_signal")).isEqualTo(true);
        assertThat(row.get("provider")).isEqualTo("INTERNAL_AUTH");
        assertThat(row.get("client_type")).isEqualTo("WEB");
        assertThat(row.get("endpoint")).isEqualTo("/api/login");
        assertThat(row.get("principal_hash")).isEqualTo(PRINCIPAL_HASH);
        assertThat(row.get("ip_hash")).isEqualTo(IP_HASH);
        assertThat(row.get("user_agent_family")).isEqualTo("CHROME");
    }

    @Test
    @DisplayName("알 수 없는 errorType은 UNKNOWN_AUTH_ERROR로 정규화된다")
    void unknown_error_type은_unknown으로_정규화된다() {
        String requestId = "REQ-TAXONOMY-UNKNOWN-" + UUID.randomUUID();

        authErrorWriter.record(newCommand(
                requestId,
                "provider-made-up-value",
                null,
                null,
                null,
                null,
                null,
                null
        ));

        Map<String, Object> row = jdbcTemplate.queryForMap("""
                select error_type,
                       auth_failure_severity,
                       auth_failure_retryable,
                       auth_failure_security_signal
                  from auth_error
                 where request_id = ?
                """, requestId);

        assertThat(row.get("error_type")).isEqualTo("UNKNOWN_AUTH_ERROR");
        assertThat(row.get("auth_failure_severity")).isEqualTo("MEDIUM");
        assertThat(row.get("auth_failure_retryable")).isEqualTo(false);
        assertThat(row.get("auth_failure_security_signal")).isEqualTo(false);
    }

    @Test
    @DisplayName("동일 requestId 재호출은 taxonomy 값이 달라도 기존 auth_error/outbox를 재사용한다")
    void 동일_requestId는_taxonomy가_달라도_기존_결과를_재사용한다() {
        String requestId = "REQ-TAXONOMY-IDEMP-" + UUID.randomUUID();

        var first = authErrorWriter.record(newCommand(
                requestId,
                "AUTH_PROVIDER_TIMEOUT",
                "OAUTH_PROVIDER",
                "MOBILE",
                "/api/mobile/login",
                PRINCIPAL_HASH,
                IP_HASH,
                "ANDROID"
        ));
        var second = authErrorWriter.record(newCommand(
                requestId,
                "TOKEN_INVALID_SIGNATURE",
                "INTERNAL_AUTH",
                "WEB",
                "/api/login",
                null,
                null,
                "CHROME"
        ));

        assertThat(second.authErrorId()).isEqualTo(first.authErrorId());
        assertThat(second.outboxId()).isEqualTo(first.outboxId());
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from auth_error where request_id = ?",
                Long.class,
                requestId
        )).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
                "select error_type from auth_error where request_id = ?",
                String.class,
                requestId
        )).isEqualTo("AUTH_PROVIDER_TIMEOUT");
    }

    private AuthErrorWriteCommand newCommand(String requestId,
                                             String errorType,
                                             String provider,
                                             String clientType,
                                             String endpoint,
                                             String principalHash,
                                             String ipHash,
                                             String userAgentFamily) {
        return new AuthErrorWriteCommand(
                requestId,
                OffsetDateTime.now(),
                401,
                errorType,
                provider,
                clientType,
                endpoint,
                principalHash,
                ipHash,
                userAgentFamily,
                "POST",
                "/api/login",
                "203.0.113.10",
                "JUnit",
                "raw-user-must-not-be-used-for-taxonomy",
                "raw-session-must-not-be-used-for-taxonomy",
                "IllegalStateException",
                "test exception",
                null,
                null,
                "stacktrace"
        );
    }
}
