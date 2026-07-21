package com.yunhwan.auth.error.mcp;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.Properties;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;

class DiagnosticRepository {

    private final DatabaseConfig config;
    private final Semaphore queryPermits;

    DiagnosticRepository(DatabaseConfig config) {
        this.config = config;
        this.queryPermits = new Semaphore(config.maxConcurrentQueries(), true);
    }

    Map<String, Object> getAuthErrorSummary(DiagnosticQuery query) throws Exception {
        List<Object> params = new ArrayList<>();
        String where = authErrorWindowWhere(query, params, "occurred_at");
        List<Map<String, Object>> byType = select("""
                select error_type,
                       auth_failure_severity,
                       auth_failure_retryable,
                       auth_failure_security_signal,
                       count(*) as error_count,
                       min(occurred_at) as first_seen_at,
                       max(occurred_at) as last_seen_at
                  from auth_error
                """ + where + """
                 group by error_type, auth_failure_severity, auth_failure_retryable, auth_failure_security_signal
                 order by error_count desc, error_type asc
                """, params);
        long total = byType.stream().mapToLong(row -> number(row.get("error_count")).longValue()).sum();
        long securitySignals = byType.stream()
                .filter(row -> Boolean.TRUE.equals(row.get("auth_failure_security_signal")))
                .mapToLong(row -> number(row.get("error_count")).longValue())
                .sum();
        return Map.of(
                "windowHours", query.hoursBack(),
                "totalErrorCount", total,
                "securitySignalCount", securitySignals,
                "byType", byType
        );
    }

    List<Map<String, Object>> getAuthErrorTrend(DiagnosticQuery query) throws Exception {
        List<Object> params = new ArrayList<>();
        String where = authErrorWindowWhere(query, params, "occurred_at");
        params.add(query.limit());
        return select("""
                select date_trunc('hour', occurred_at) as bucket_hour,
                       error_type,
                       count(*) as error_count
                  from auth_error
                """ + where + """
                 group by date_trunc('hour', occurred_at), error_type
                 order by bucket_hour desc, error_count desc
                 limit ?
                """, params);
    }

    List<Map<String, Object>> getTopAuthErrorTypes(DiagnosticQuery query) throws Exception {
        List<Object> params = new ArrayList<>();
        String where = contextWindowWhere(query, params, "occurred_at");
        params.add(query.limit());
        return select("""
                select error_type,
                       provider,
                       client_type,
                       http_status,
                       endpoint,
                       count(*) as error_count
                  from auth_error
                """ + where + """
                 group by error_type, provider, client_type, http_status, endpoint
                 order by error_count desc, error_type asc
                 limit ?
                """, params);
    }

    List<Map<String, Object>> getAuthErrorClusters(DiagnosticQuery query) throws Exception {
        List<Object> params = new ArrayList<>();
        String where = clusterWhere(query, params);
        params.add(query.limit());
        return select("""
                select error_type,
                       provider,
                       stack_hash,
                       auth_failure_severity,
                       count(*) as error_count,
                       count(distinct principal_hash) filter (where principal_hash is not null) as principal_hash_count,
                       count(distinct ip_hash) filter (where ip_hash is not null) as ip_hash_count,
                       min(occurred_at) as first_seen_at,
                       max(occurred_at) as last_seen_at
                  from auth_error
                """ + where + """
                 group by error_type, provider, stack_hash, auth_failure_severity
                 order by error_count desc, last_seen_at desc
                 limit ?
                """, params);
    }

    List<Map<String, Object>> getDlqSummary(DiagnosticQuery query) throws Exception {
        List<Object> params = new ArrayList<>();
        params.add(query.hoursBack());
        params.add(query.limit());
        return select("""
                select date_trunc('hour', last_seen_at) as bucket_hour,
                       reason_code,
                       replay_status,
                       dlq_queue,
                       event_type,
                       count(*) as message_count,
                       sum(delivery_count) as delivery_count_sum,
                       max(retry_count) as retry_count_max,
                       min(first_seen_at) as first_seen_at,
                       max(last_seen_at) as last_seen_at
                  from dead_letter_message
                 where last_seen_at >= now() - (? * interval '1 hour')
                 group by date_trunc('hour', last_seen_at), reason_code, replay_status, dlq_queue, event_type
                 order by message_count desc, last_seen_at desc
                 limit ?
                """, params);
    }

    List<Map<String, Object>> getRetrySummary(DiagnosticQuery query) throws Exception {
        List<Object> params = new ArrayList<>();
        params.add(query.hoursBack());
        params.add(query.limit());
        return select("""
                select date_trunc('hour', created_at) as bucket_hour,
                       event_type,
                       status,
                       count(*) as request_count,
                       sum(publish_retry_count) as publish_retry_count_sum,
                       max(publish_retry_count) as publish_retry_count_max,
                       min(next_publish_at) as earliest_next_publish_at,
                       max(updated_at) as last_updated_at
                  from retry_publish_request
                 where created_at >= now() - (? * interval '1 hour')
                 group by date_trunc('hour', created_at), event_type, status
                 order by request_count desc, last_updated_at desc
                 limit ?
                """, params);
    }

    Map<String, Object> getIncidentSnapshot(DiagnosticQuery query) throws Exception {
        return Map.of(
                "windowHours", query.hoursBack(),
                "summary", getAuthErrorSummary(query),
                "topTypes", getTopAuthErrorTypes(new DiagnosticQuery(query.hoursBack(), 5, query.provider(), query.errorType(), null, null, null)),
                "clusters", getAuthErrorClusters(new DiagnosticQuery(query.hoursBack(), 5, query.provider(), query.errorType(), null, null, null)),
                "retry", getRetrySummary(new DiagnosticQuery(query.hoursBack(), 5, null, null, null, null, null)),
                "dlq", getDlqSummary(new DiagnosticQuery(query.hoursBack(), 5, null, null, null, null, null))
        );
    }

    Map<String, Object> traceAuthError(DiagnosticQuery query) throws Exception {
        if (query.requestId() == null && query.traceId() == null && query.outboxId() == null) {
            throw new IllegalArgumentException("requestId, traceId, or outboxId is required.");
        }
        List<Object> authParams = new ArrayList<>();
        String authWhere = traceWhere(query, authParams);
        List<Map<String, Object>> authErrors = select("""
                select id,
                       request_id,
                       correlation_id,
                       trace_id,
                       occurred_at,
                       received_at,
                       source_service,
                       environment,
                       error_type,
                       auth_failure_severity,
                       auth_failure_retryable,
                       auth_failure_security_signal,
                       provider,
                       client_type,
                       endpoint,
                       principal_hash,
                       ip_hash,
                       stack_hash,
                       status,
                       dedup_key
                  from auth_error
                """ + authWhere + """
                 order by occurred_at desc
                 limit 20
                """, authParams);

        List<Object> outboxParams = new ArrayList<>();
        String outboxWhere = outboxWhere(query, authErrors, outboxParams);
        List<Map<String, Object>> outbox = select("""
                select id,
                       aggregate_type,
                       aggregate_id,
                       event_type,
                       idempotency_key,
                       status,
                       retry_count,
                       max_retries,
                       payload_hash,
                       created_at,
                       updated_at,
                       published_at,
                       (last_error is not null) as has_last_error
                  from outbox_message
                """ + outboxWhere + """
                 order by created_at desc
                 limit 20
                """, outboxParams);

        List<Object> dlqParams = new ArrayList<>();
        String dlqWhere = dlqWhere(query, outbox, dlqParams);
        List<Map<String, Object>> dlq = select("""
                select id,
                       dlq_queue,
                       outbox_id,
                       event_type,
                       payload_hash,
                       reason_code,
                       retry_count,
                       delivery_count,
                       replay_status,
                       first_seen_at,
                       last_seen_at
                  from dead_letter_message
                """ + dlqWhere + """
                 order by last_seen_at desc
                 limit 20
                """, dlqParams);

        return Map.of(
                "authErrors", authErrors,
                "outboxMessages", outbox,
                "deadLetters", dlq,
                "payloadPolicy", "raw payload, raw IP, raw user id, tokens, and credentials are not returned"
        );
    }

    private List<Map<String, Object>> select(String sql, List<Object> params) throws Exception {
        if (!queryPermits.tryAcquire()) {
            throw new DiagnosticQueryRejectedException("동시 진단 조회 한도를 초과했습니다. 잠시 후 다시 시도하세요.");
        }
        Properties properties = new Properties();
        properties.setProperty("user", config.username());
        properties.setProperty("password", config.password());
        properties.setProperty("connectTimeout", String.valueOf(config.connectTimeoutSeconds()));
        try (Connection connection = DriverManager.getConnection(config.url(), properties)) {
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            try (PreparedStatement readOnly = connection.prepareStatement("set transaction read only")) {
                readOnly.execute();
            }
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setQueryTimeout(config.queryTimeoutSeconds());
                for (int i = 0; i < params.size(); i++) {
                    statement.setObject(i + 1, params.get(i));
                }
                try (ResultSet rs = statement.executeQuery()) {
                    List<Map<String, Object>> rows = rows(rs);
                    connection.commit();
                    return rows;
                }
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            }
        } finally {
            queryPermits.release();
        }
    }

    private static String authErrorWindowWhere(DiagnosticQuery query, List<Object> params, String timeColumn) {
        List<String> clauses = new ArrayList<>();
        params.add(query.hoursBack());
        clauses.add(timeColumn + " >= now() - (? * interval '1 hour')");
        if (query.provider() != null) {
            clauses.add("provider = ?");
            params.add(query.provider());
        }
        if (query.errorType() != null) {
            clauses.add("error_type = ?");
            params.add(query.errorType());
        }
        return where(clauses);
    }

    private static String contextWindowWhere(DiagnosticQuery query, List<Object> params, String timeColumn) {
        List<String> clauses = new ArrayList<>();
        params.add(query.hoursBack());
        clauses.add(timeColumn + " >= now() - (? * interval '1 hour')");
        if (query.provider() != null) {
            clauses.add("provider = ?");
            params.add(query.provider());
        }
        if (query.errorType() != null) {
            clauses.add("error_type = ?");
            params.add(query.errorType());
        }
        return where(clauses);
    }

    private static String clusterWhere(DiagnosticQuery query, List<Object> params) {
        List<String> clauses = new ArrayList<>();
        params.add(query.hoursBack());
        clauses.add("occurred_at >= now() - (? * interval '1 hour')");
        if (query.provider() != null) {
            clauses.add("provider = ?");
            params.add(query.provider());
        }
        if (query.errorType() != null) {
            clauses.add("error_type = ?");
            params.add(query.errorType());
        }
        return where(clauses);
    }

    private static String traceWhere(DiagnosticQuery query, List<Object> params) {
        List<String> clauses = new ArrayList<>();
        if (query.requestId() != null) {
            clauses.add("request_id = ?");
            params.add(query.requestId());
        }
        if (query.traceId() != null) {
            clauses.add("trace_id = ?");
            params.add(query.traceId());
        }
        if (query.outboxId() != null) {
            clauses.add("id in (select cast(aggregate_id as bigint) from outbox_message where id = ? and aggregate_id ~ '^[0-9]+$')");
            params.add(query.outboxId());
        }
        return whereAny(clauses);
    }

    private static String outboxWhere(DiagnosticQuery query, List<Map<String, Object>> authErrors, List<Object> params) {
        List<String> clauses = new ArrayList<>();
        if (query.outboxId() != null) {
            clauses.add("id = ?");
            params.add(query.outboxId());
        }
        for (Map<String, Object> row : authErrors) {
            clauses.add("aggregate_id = ?");
            params.add(String.valueOf(row.get("id")));
        }
        return whereAny(clauses);
    }

    private static String dlqWhere(DiagnosticQuery query, List<Map<String, Object>> outbox, List<Object> params) {
        List<String> clauses = new ArrayList<>();
        if (query.outboxId() != null) {
            clauses.add("outbox_id = ?");
            params.add(query.outboxId());
        }
        for (Map<String, Object> row : outbox) {
            clauses.add("outbox_id = ?");
            params.add(row.get("id"));
        }
        return whereAny(clauses);
    }

    private static String where(List<String> clauses) {
        return " where " + String.join(" and ", clauses) + "\n";
    }

    private static String whereAny(List<String> clauses) {
        if (clauses.isEmpty()) {
            return " where false\n";
        }
        return " where (" + String.join(" or ", clauses) + ")\n";
    }

    private static List<Map<String, Object>> rows(ResultSet rs) throws Exception {
        ResultSetMetaData metadata = rs.getMetaData();
        List<Map<String, Object>> rows = new ArrayList<>();
        while (rs.next()) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 1; i <= metadata.getColumnCount(); i++) {
                Object value = rs.getObject(i);
                if (value instanceof Timestamp timestamp) {
                    value = timestamp.toInstant().toString();
                } else if (value instanceof OffsetDateTime offsetDateTime) {
                    value = offsetDateTime.toString();
                }
                row.put(metadata.getColumnLabel(i), value);
            }
            rows.add(row);
        }
        return rows;
    }

    private static Number number(Object value) {
        return value instanceof Number number ? number : 0;
    }

    static final class DiagnosticQueryRejectedException extends RuntimeException {
        DiagnosticQueryRejectedException(String message) {
            super(message);
        }
    }
}
