package com.yunhwan.auth.error.mcp;

import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
class DiagnosticMcpTools {

    private final DiagnosticRepository repository;

    DiagnosticMcpTools(DiagnosticRepository repository) {
        this.repository = repository;
    }

    @McpTool(name = "get_auth_error_summary", description = "기간 내 인증 실패 총량, 유형별 분포, 보안 신호 합계를 조회한다.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    Object getAuthErrorSummary(
            @McpToolParam(description = "조회 기간(시간), 1~168", required = false) Integer hoursBack,
            @McpToolParam(description = "인증 provider 필터", required = false) String provider,
            @McpToolParam(description = "인증 실패 유형 필터", required = false) String errorType) {
        return execute(query -> repository.getAuthErrorSummary(query), query(hoursBack, null, provider, errorType, null, null, null));
    }

    @McpTool(name = "get_auth_error_trend", description = "시간 bucket별 인증 실패 추이를 조회한다.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    Object getAuthErrorTrend(
            @McpToolParam(description = "조회 기간(시간), 1~168", required = false) Integer hoursBack,
            @McpToolParam(description = "인증 provider 필터", required = false) String provider,
            @McpToolParam(description = "인증 실패 유형 필터", required = false) String errorType,
            @McpToolParam(description = "최대 결과 수, 1~50", required = false) Integer limit) {
        return execute(query -> repository.getAuthErrorTrend(query), query(hoursBack, limit, provider, errorType, null, null, null));
    }

    @McpTool(name = "get_top_auth_error_types", description = "기간 내 상위 인증 실패 유형을 조회한다.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    Object getTopAuthErrorTypes(
            @McpToolParam(description = "조회 기간(시간), 1~168", required = false) Integer hoursBack,
            @McpToolParam(description = "인증 provider 필터", required = false) String provider,
            @McpToolParam(description = "최대 결과 수, 1~50", required = false) Integer limit) {
        return execute(query -> repository.getTopAuthErrorTypes(query), query(hoursBack, limit, provider, null, null, null, null));
    }

    @McpTool(name = "get_auth_error_clusters", description = "errorType/provider/stackHash 기준 상위 cluster 후보를 조회한다.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    Object getAuthErrorClusters(
            @McpToolParam(description = "조회 기간(시간), 1~168", required = false) Integer hoursBack,
            @McpToolParam(description = "인증 provider 필터", required = false) String provider,
            @McpToolParam(description = "인증 실패 유형 필터", required = false) String errorType,
            @McpToolParam(description = "최대 결과 수, 1~50", required = false) Integer limit) {
        return execute(query -> repository.getAuthErrorClusters(query), query(hoursBack, limit, provider, errorType, null, null, null));
    }

    @McpTool(name = "get_dlq_summary", description = "DLQ reason/replay status 분포를 조회한다.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    Object getDlqSummary(
            @McpToolParam(description = "조회 기간(시간), 1~168", required = false) Integer hoursBack,
            @McpToolParam(description = "최대 결과 수, 1~50", required = false) Integer limit) {
        return execute(query -> repository.getDlqSummary(query), query(hoursBack, limit, null, null, null, null, null));
    }

    @McpTool(name = "get_retry_summary", description = "retry publish request 상태와 재발행 압력을 조회한다.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    Object getRetrySummary(
            @McpToolParam(description = "조회 기간(시간), 1~168", required = false) Integer hoursBack,
            @McpToolParam(description = "최대 결과 수, 1~50", required = false) Integer limit) {
        return execute(query -> repository.getRetrySummary(query), query(hoursBack, limit, null, null, null, null, null));
    }

    @McpTool(name = "get_incident_snapshot", description = "인증 실패, cluster, retry, DLQ 요약을 한 번에 조회한다.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    Object getIncidentSnapshot(
            @McpToolParam(description = "조회 기간(시간), 1~168", required = false) Integer hoursBack,
            @McpToolParam(description = "인증 provider 필터", required = false) String provider,
            @McpToolParam(description = "인증 실패 유형 필터", required = false) String errorType) {
        return execute(query -> repository.getIncidentSnapshot(query), query(hoursBack, null, provider, errorType, null, null, null));
    }

    @McpTool(name = "trace_auth_error", description = "requestId, traceId, outboxId 중 하나로 원장 흐름을 read-only로 추적한다. payload 원문은 반환하지 않는다.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    Object traceAuthError(
            @McpToolParam(description = "요청 식별자", required = false) String requestId,
            @McpToolParam(description = "분산 추적 식별자", required = false) String traceId,
            @McpToolParam(description = "Outbox 원장 ID", required = false) Long outboxId) {
        return execute(query -> repository.traceAuthError(query), query(null, null, null, null, requestId, traceId, outboxId));
    }

    private static DiagnosticQuery query(
            Integer hoursBack,
            Integer limit,
            String provider,
            String errorType,
            String requestId,
            String traceId,
            Long outboxId
    ) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        putIfNotNull(arguments, "hoursBack", hoursBack);
        putIfNotNull(arguments, "limit", limit);
        putIfNotNull(arguments, "provider", provider);
        putIfNotNull(arguments, "errorType", errorType);
        putIfNotNull(arguments, "requestId", requestId);
        putIfNotNull(arguments, "traceId", traceId);
        putIfNotNull(arguments, "outboxId", outboxId);
        return DiagnosticQuery.from(arguments);
    }

    private static void putIfNotNull(Map<String, Object> arguments, String key, Object value) {
        if (value != null) {
            arguments.put(key, value);
        }
    }

    private static Object execute(RepositoryQuery operation, DiagnosticQuery query) {
        try {
            return operation.execute(query);
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            System.err.printf("MCP diagnostic query failed: %s%n", exception.getClass().getSimpleName());
            throw new IllegalStateException("진단 조회에 실패했습니다. MCP 서버의 stderr 로그를 확인하세요.");
        }
    }

    @FunctionalInterface
    private interface RepositoryQuery {
        Object execute(DiagnosticQuery query) throws Exception;
    }
}
