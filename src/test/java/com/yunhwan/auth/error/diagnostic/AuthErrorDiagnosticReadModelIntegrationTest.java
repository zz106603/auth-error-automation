package com.yunhwan.auth.error.diagnostic;

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

@DisplayName("[14] Auth error diagnostic read model 통합 테스트")
class AuthErrorDiagnosticReadModelIntegrationTest extends AbstractIntegrationTest {

    private static final String PRINCIPAL_HASH =
            "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc";
    private static final String IP_HASH =
            "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd";

    @Autowired
    AuthErrorWriter authErrorWriter;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("auth_error 통계 view는 type/context/cluster 집계를 제공한다")
    void auth_error_통계_view는_type_context_cluster_집계를_제공한다() {
        String provider = "READMODEL_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();

        authErrorWriter.record(command("TOKEN_INVALID_SIGNATURE", provider, "WEB", "/api/login", PRINCIPAL_HASH, IP_HASH));
        authErrorWriter.record(command("TOKEN_INVALID_SIGNATURE", provider, "WEB", "/api/login", PRINCIPAL_HASH, IP_HASH));
        authErrorWriter.record(command("AUTH_PROVIDER_TIMEOUT", provider, "MOBILE", "/api/mobile/login", null, null));

        Map<String, Object> typeStats = jdbcTemplate.queryForMap("""
                select error_count,
                       auth_failure_severity,
                       auth_failure_security_signal
                  from auth_error_hourly_type_stats
                 where error_type = 'TOKEN_INVALID_SIGNATURE'
                   and bucket_hour = date_trunc('hour', now())
                """);

        assertThat(((Number) typeStats.get("error_count")).longValue())
                .isGreaterThanOrEqualTo(2L);
        assertThat(typeStats.get("auth_failure_severity")).isEqualTo("HIGH");
        assertThat(typeStats.get("auth_failure_security_signal")).isEqualTo(true);

        Map<String, Object> context = jdbcTemplate.queryForMap("""
                select error_count,
                       http_status,
                       endpoint
                  from auth_error_context_distribution
                 where error_type = 'TOKEN_INVALID_SIGNATURE'
                   and provider = ?
                   and client_type = 'WEB'
                   and endpoint = '/api/login'
                """, provider);

        assertThat(((Number) context.get("error_count")).longValue()).isEqualTo(2L);
        assertThat(context.get("http_status")).isEqualTo(401);
        assertThat(context.get("endpoint")).isEqualTo("/api/login");

        Map<String, Object> cluster = jdbcTemplate.queryForMap("""
                select error_count,
                       principal_hash_count,
                       ip_hash_count
                  from auth_error_cluster_summary
                 where error_type = 'TOKEN_INVALID_SIGNATURE'
                   and provider = ?
                """, provider);

        assertThat(((Number) cluster.get("error_count")).longValue()).isEqualTo(2L);
        assertThat(((Number) cluster.get("principal_hash_count")).longValue()).isEqualTo(1L);
        assertThat(((Number) cluster.get("ip_hash_count")).longValue()).isEqualTo(1L);
    }

    @Test
    @DisplayName("retry/DLQ 요약 view는 운영 원장을 변경하지 않고 집계한다")
    void retry_dlq_요약_view는_원장을_집계한다() {
        long outboxId = Math.abs(UUID.randomUUID().getMostSignificantBits());
        String retryEventType = "diagnostic.retry." + UUID.randomUUID();
        String dlqEventType = "diagnostic.dlq." + UUID.randomUUID();
        String dedupeKey = "diagnostic-" + UUID.randomUUID();

        jdbcTemplate.update("""
                insert into retry_publish_request
                  (source_outbox_id, event_type, aggregate_type, payload, retry_count, next_retry_at,
                   status, publish_retry_count, next_publish_at)
                values
                  (?, ?, 'AUTH_ERROR', cast(? as jsonb), 2, now(), 'PENDING', 3, now())
                """, outboxId, retryEventType, "{\"authErrorId\":1}");

        jdbcTemplate.update("""
                insert into dead_letter_message
                  (dedupe_key, dlq_queue, outbox_id, event_type, aggregate_type, payload,
                   payload_hash, payload_size_bytes, reason_code, retry_count, delivery_count, replay_status)
                values
                  (?, 'auth.error.recorded.q.dlq', ?, ?, 'AUTH_ERROR', '{}',
                   ?, 2, 'RETRY_EXHAUSTED', 5, 2, 'REPLAYABLE')
                """, dedupeKey, outboxId, dlqEventType,
                "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee");

        Map<String, Object> retry = jdbcTemplate.queryForMap("""
                select request_count,
                       publish_retry_count_sum,
                       publish_retry_count_max
                  from retry_publish_request_summary
                 where event_type = ?
                   and status = 'PENDING'
                """, retryEventType);

        assertThat(((Number) retry.get("request_count")).longValue()).isEqualTo(1L);
        assertThat(((Number) retry.get("publish_retry_count_sum")).longValue()).isEqualTo(3L);
        assertThat(((Number) retry.get("publish_retry_count_max")).longValue()).isEqualTo(3L);

        Map<String, Object> dlq = jdbcTemplate.queryForMap("""
                select message_count,
                       delivery_count_sum,
                       retry_count_max,
                       replay_status
                  from dead_letter_reason_summary
                 where event_type = ?
                   and reason_code = 'RETRY_EXHAUSTED'
                """, dlqEventType);

        assertThat(((Number) dlq.get("message_count")).longValue()).isEqualTo(1L);
        assertThat(((Number) dlq.get("delivery_count_sum")).longValue()).isEqualTo(2L);
        assertThat(((Number) dlq.get("retry_count_max")).longValue()).isEqualTo(5L);
        assertThat(dlq.get("replay_status")).isEqualTo("REPLAYABLE");
    }

    private AuthErrorWriteCommand command(String errorType,
                                          String provider,
                                          String clientType,
                                          String endpoint,
                                          String principalHash,
                                          String ipHash) {
        return new AuthErrorWriteCommand(
                "REQ-DIAG-" + UUID.randomUUID(),
                OffsetDateTime.now(),
                401,
                errorType,
                provider,
                clientType,
                endpoint,
                principalHash,
                ipHash,
                "CHROME",
                "POST",
                endpoint,
                "203.0.113.10",
                "JUnit",
                "raw-user-not-for-readmodel",
                "raw-session-not-for-readmodel",
                "IllegalStateException",
                "test exception",
                null,
                null,
                "stacktrace"
        );
    }
}
