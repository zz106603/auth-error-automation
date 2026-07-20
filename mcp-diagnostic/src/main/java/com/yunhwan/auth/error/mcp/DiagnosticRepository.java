package com.yunhwan.auth.error.mcp;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class DiagnosticRepository {

    private final DatabaseConfig config;

    DiagnosticRepository(DatabaseConfig config) {
        this.config = config;
    }

    Map<String, Object> getAuthErrorSummary(DiagnosticQuery query) throws Exception {
        List<Object> params = new ArrayList<>();
        String where = authErrorWindowWhere(query, params, "bucket_hour");
        List<Map<String, Object>> byType = select("""
                select error_type,
                       auth_failure_severity,
                       auth_failure_retryable,
                       auth_failure_security_signal,
                       sum(error_count) as error_count,
                       min(first_seen_at) as first_seen_at,
                       max(last_seen_at) as last_seen_at
                  from auth_error_hourly_type_stats
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
        String where = authErrorWindowWhere(query, params, "bucket_hour");
        params.add(query.limit());
        return select("""
                select bucket_hour,
                       error_type,
                       sum(error_count) as error_count
                  from auth_error_hourly_type_stats
                """ + where + """
                 group by bucket_hour, error_type
                 order by bucket_hour desc, error_count desc
                 limit ?
                """, params);
    }

    List<Map<String, Object>> getTopAuthErrorTypes(DiagnosticQuery query) throws Exception {
        List<Object> params = new ArrayList<>();
        String where = contextWindowWhere(query, params, "bucket_hour");
        params.add(query.limit());
        return select("""
                select error_type,
                       provider,
                       client_type,
                       http_status,
                       endpoint,
                       sum(error_count) as error_count
                  from auth_error_context_distribution
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
                       error_count,
                       principal_hash_count,
                       ip_hash_count,
                       first_seen_at,
                       last_seen_at
                  from auth_error_cluster_summary
                """ + where + """
                 order by error_count desc, last_seen_at desc
                 limit ?
                """, params);
    }

    List<Map<String, Object>> getDlqSummary(DiagnosticQuery query) throws Exception {
        List<Object> params = new ArrayList<>();
        params.add(query.hoursBack());
        params.add(query.limit());
        return select("""
                select bucket_hour,
                       reason_code,
                       replay_status,
                       dlq_queue,
                       event_type,
                       message_count,
                       delivery_count_sum,
                       retry_count_max,
                       first_seen_at,
                       last_seen_at
                  from dead_letter_reason_summary
                 where bucket_hour >= date_trunc('hour', now() - (? * interval '1 hour'))
                 order by message_count desc, last_seen_at desc
                 limit ?
                """, params);
    }

    List<Map<String, Object>> getRetrySummary(DiagnosticQuery query) throws Exception {
        List<Object> params = new ArrayList<>();
        params.add(query.hoursBack());
        params.add(query.limit());
        return select("""
                select bucket_hour,
                       event_type,
                       status,
                       request_count,
                       publish_retry_count_sum,
                       publish_retry_count_max,
                       earliest_next_publish_at,
                       last_updated_at
                  from retry_publish_request_summary
                 where bucket_hour >= date_trunc('hour', now() - (? * interval '1 hour'))
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
                       last_error
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
        try (Connection connection = DriverManager.getConnection(config.url(), config.username(), config.password())) {
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            try (PreparedStatement readOnly = connection.prepareStatement("set transaction read only")) {
                readOnly.execute();
            }
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
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
        }
    }

    private static String authErrorWindowWhere(DiagnosticQuery query, List<Object> params, String timeColumn) {
        List<String> clauses = new ArrayList<>();
        params.add(query.hoursBack());
        clauses.add(timeColumn + " >= date_trunc('hour', now() - (? * interval '1 hour'))");
        if (query.errorType() != null) {
            clauses.add("error_type = ?");
            params.add(query.errorType());
        }
        return where(clauses);
    }

    private static String contextWindowWhere(DiagnosticQuery query, List<Object> params, String timeColumn) {
        List<String> clauses = new ArrayList<>();
        params.add(query.hoursBack());
        clauses.add(timeColumn + " >= date_trunc('hour', now() - (? * interval '1 hour'))");
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
        clauses.add("last_seen_at >= now() - (? * interval '1 hour')");
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
}
