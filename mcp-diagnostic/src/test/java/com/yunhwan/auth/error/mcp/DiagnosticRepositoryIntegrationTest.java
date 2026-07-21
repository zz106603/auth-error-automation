package com.yunhwan.auth.error.mcp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class DiagnosticRepositoryIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private DiagnosticRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        repository = new DiagnosticRepository(new DatabaseConfig(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), 3, 5, 2
        ));
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("drop table if exists dead_letter_message, retry_publish_request, outbox_message, auth_error cascade");
            statement.execute("""
                    create table auth_error (
                        id bigserial primary key, request_id varchar(100), correlation_id varchar(100), trace_id varchar(64),
                        occurred_at timestamptz not null, received_at timestamptz not null, source_service varchar(100) not null,
                        environment varchar(20) not null, error_type varchar(50) not null,
                        auth_failure_severity varchar(10) not null, auth_failure_retryable boolean not null,
                        auth_failure_security_signal boolean not null, provider varchar(100), client_type varchar(50),
                        http_status integer, endpoint text, principal_hash varchar(64), ip_hash varchar(64), stack_hash varchar(64),
                        status varchar(20) not null, dedup_key varchar(64)
                    )
                    """);
            statement.execute("""
                    create table outbox_message (
                        id bigserial primary key, aggregate_type varchar(100), aggregate_id varchar(100), event_type varchar(200),
                        idempotency_key varchar(200), status varchar(30), retry_count integer, max_retries integer,
                        payload_hash varchar(64), created_at timestamptz, updated_at timestamptz, published_at timestamptz,
                        last_error text
                    )
                    """);
            statement.execute("""
                    create table dead_letter_message (
                        id bigserial primary key, dlq_queue varchar(200), outbox_id bigint, event_type varchar(200),
                        payload_hash varchar(64), reason_code varchar(80), retry_count integer, delivery_count integer,
                        replay_status varchar(30), first_seen_at timestamptz, last_seen_at timestamptz
                    )
                    """);
            statement.execute("""
                    create table retry_publish_request (
                        id bigserial primary key, event_type varchar(200), status varchar(30), publish_retry_count integer,
                        next_publish_at timestamptz, created_at timestamptz, updated_at timestamptz
                    )
                    """);
            statement.execute("""
                    create view retry_publish_request_summary as
                    select date_trunc('hour', created_at) bucket_hour, event_type, status, count(*) request_count,
                           sum(publish_retry_count) publish_retry_count_sum, max(publish_retry_count) publish_retry_count_max,
                           min(next_publish_at) earliest_next_publish_at, max(updated_at) last_updated_at
                      from retry_publish_request group by date_trunc('hour', created_at), event_type, status
                    """);
            statement.execute("""
                    create view dead_letter_reason_summary as
                    select date_trunc('hour', last_seen_at) bucket_hour, reason_code, replay_status, dlq_queue, event_type,
                           count(*) message_count, sum(delivery_count) delivery_count_sum, max(retry_count) retry_count_max,
                           min(first_seen_at) first_seen_at, max(last_seen_at) last_seen_at
                      from dead_letter_message group by date_trunc('hour', last_seen_at), reason_code, replay_status, dlq_queue, event_type
                    """);
        }
    }

    @Test
    void provider와_정확한_시간범위를_summary_trend_cluster에_적용한다() throws Exception {
        execute("""
                insert into auth_error
                    (request_id, trace_id, occurred_at, received_at, source_service, environment, error_type,
                     auth_failure_severity, auth_failure_retryable, auth_failure_security_signal, provider, client_type,
                     http_status, endpoint, principal_hash, ip_hash, stack_hash, status, dedup_key)
                values
                    ('req-recent', 'trace-recent', now() - interval '10 minutes', now(), 'auth-api', 'test',
                     'TOKEN_INVALID_SIGNATURE', 'HIGH', false, true, 'google', 'WEB', 401, '/login', repeat('a', 64), repeat('b', 64), repeat('c', 64), 'NEW', 'd1'),
                    ('req-other', 'trace-other', now() - interval '5 minutes', now(), 'auth-api', 'test',
                     'TOKEN_INVALID_SIGNATURE', 'HIGH', false, true, 'github', 'WEB', 401, '/login', repeat('d', 64), repeat('e', 64), repeat('c', 64), 'NEW', 'd2'),
                    ('req-old', 'trace-old', now() - interval '2 hours', now(), 'auth-api', 'test',
                     'TOKEN_INVALID_SIGNATURE', 'HIGH', false, true, 'google', 'WEB', 401, '/login', repeat('f', 64), repeat('0', 64), repeat('c', 64), 'NEW', 'd3')
                """);
        DiagnosticQuery query = DiagnosticQuery.from(Map.of(
                "hoursBack", 1, "provider", "google", "errorType", "TOKEN_INVALID_SIGNATURE"
        ));

        Map<String, Object> summary = repository.getAuthErrorSummary(query);
        List<Map<String, Object>> trend = repository.getAuthErrorTrend(query);
        List<Map<String, Object>> clusters = repository.getAuthErrorClusters(query);

        assertThat(summary.get("totalErrorCount")).isEqualTo(1L);
        assertThat(trend).singleElement().satisfies(row -> assertThat(row.get("error_count")).isEqualTo(1L));
        assertThat(clusters).singleElement().satisfies(row -> {
            assertThat(row.get("provider")).isEqualTo("google");
            assertThat(row.get("error_count")).isEqualTo(1L);
            assertThat(row.get("principal_hash_count")).isEqualTo(1L);
        });
    }

    @Test
    void retry와_dlq를_집계하고_trace에서_자유형식_오류를_노출하지_않는다() throws Exception {
        execute("""
                insert into auth_error
                    (id, request_id, trace_id, occurred_at, received_at, source_service, environment, error_type,
                     auth_failure_severity, auth_failure_retryable, auth_failure_security_signal, provider, status)
                values (101, 'req-safe', 'trace-safe', now(), now(), 'auth-api', 'test', 'INVALID_CREDENTIALS',
                        'LOW', false, false, 'google', 'NEW');
                insert into outbox_message
                    (id, aggregate_type, aggregate_id, event_type, idempotency_key, status, retry_count, max_retries,
                     payload_hash, created_at, updated_at, last_error)
                values (201, 'AUTH_ERROR', '101', 'AUTH_ERROR_DETECTED_V1', 'key-1', 'DEAD', 3, 3,
                        repeat('1', 64), now(), now(), 'token=secret internal-host=db.prod');
                insert into dead_letter_message
                    (dlq_queue, outbox_id, event_type, payload_hash, reason_code, retry_count, delivery_count,
                     replay_status, first_seen_at, last_seen_at)
                values ('auth.dlq', 201, 'AUTH_ERROR_DETECTED_V1', repeat('1', 64), 'RETRY_EXHAUSTED', 3, 4,
                        'REPLAYABLE', now(), now());
                insert into retry_publish_request
                    (event_type, status, publish_retry_count, next_publish_at, created_at, updated_at)
                values ('AUTH_ERROR_DETECTED_V1', 'DEAD', 3, now(), now(), now());
                """);

        assertThat(repository.getRetrySummary(DiagnosticQuery.from(Map.of("hoursBack", 1))))
                .singleElement().satisfies(row -> assertThat(row.get("status")).isEqualTo("DEAD"));
        assertThat(repository.getDlqSummary(DiagnosticQuery.from(Map.of("hoursBack", 1))))
                .singleElement().satisfies(row -> assertThat(row.get("reason_code")).isEqualTo("RETRY_EXHAUSTED"));

        Map<String, Object> trace = repository.traceAuthError(DiagnosticQuery.from(Map.of("outboxId", 201)));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> outbox = (List<Map<String, Object>>) trace.get("outboxMessages");
        assertThat(outbox).singleElement().satisfies(row -> {
            assertThat(row).doesNotContainKey("last_error");
            assertThat(row.get("has_last_error")).isEqualTo(true);
            assertThat(row.values()).noneMatch(value -> String.valueOf(value).contains("secret"));
        });
    }

    private void execute(String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
