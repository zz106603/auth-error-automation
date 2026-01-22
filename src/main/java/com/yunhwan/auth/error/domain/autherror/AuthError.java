package com.yunhwan.auth.error.domain.autherror;

import jakarta.persistence.*;
import lombok.*;

import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.OffsetDateTime;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
@DynamicUpdate
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "auth_error",
        indexes = {
                @Index(name = "ix_auth_error_occurred_at", columnList = "occurred_at"),
                @Index(name = "ix_auth_error_status_next_retry", columnList = "status,next_retry_at"),
                @Index(name = "ix_auth_error_service_env_time", columnList = "source_service,environment,occurred_at"),
                @Index(name = "ix_auth_error_request_id", columnList = "request_id"),
                @Index(name = "ix_auth_error_trace_id", columnList = "trace_id")
        }
)
public class AuthError {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /* ===== 식별/추적 ===== */
    @Column(name = "request_id", length = 100)
    private String requestId;
    @Column(name = "correlation_id", length = 100)
    private String correlationId;
    @Column(name = "trace_id", length = 64)
    private String traceId;
    @Column(name = "span_id", length = 16)
    private String spanId;

    /* ===== 발생/출처 ===== */
    @Column(name = "occurred_at", nullable = false)
    private OffsetDateTime occurredAt;
    @Column(name = "received_at", nullable = false)
    private OffsetDateTime receivedAt;
    @Column(name = "source_service", nullable = false, length = 100)
    private String sourceService;
    @Column(name = "source_instance", length = 100)
    private String sourceInstance;
    @Column(name = "environment", nullable = false, length = 20)
    private String environment;

    /* ===== 요청 컨텍스트 ===== */
    @Column(name = "http_method", length = 10)
    private String httpMethod;
    @Column(name = "request_uri")
    private String requestUri;
    @Column(name = "client_ip", length = 50)
    private String clientIp;
    @Column(name = "user_agent")
    private String userAgent;
    @Column(name = "user_id", length = 100)
    private String userId;
    @Column(name = "session_id", length = 200)
    private String sessionId;

    /* ===== 에러 분류 ===== */
    @Column(name = "error_domain", nullable = false, length = 50)
    private String errorDomain; // AUTH
    @Column(name = "error_code", length = 100)
    private String errorCode;
    @Column(name = "severity", nullable = false, length = 10)
    private String severity; // ERROR
    @Column(name = "category", length = 50)
    private String category;

    /* ===== 예외 ===== */
    @Column(name = "exception_class", length = 200)
    private String exceptionClass;
    @Column(name = "exception_message")
    private String exceptionMessage;
    @Column(name = "root_cause_class", length = 200)
    private String rootCauseClass;
    @Column(name = "root_cause_message")
    private String rootCauseMessage;
    @Column(name = "stacktrace")
    private String stacktrace;

    /* ===== JSONB ===== */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "request_headers", columnDefinition = "jsonb")
    private Object requestHeaders;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "request_body", columnDefinition = "jsonb")
    private Object requestBody;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "extra_context", columnDefinition = "jsonb")
    private Object extraContext;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tags", columnDefinition = "jsonb")
    private Object tags;

    /* ===== 처리 상태 ===== */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private AuthErrorStatus status;
    @Column(name = "retry_count", nullable = false)
    private int retryCount;
    @Column(name = "next_retry_at")
    private OffsetDateTime nextRetryAt;
    @Column(name = "last_processed_at")
    private OffsetDateTime lastProcessedAt;
    @Column(name = "resolved_at")
    private OffsetDateTime resolvedAt;
    @Column(name = "resolution_note")
    private String resolutionNote;

    /* ===== 중복 방지 ===== */
    @Column(name = "dedup_key", length = 64)
    private String dedupKey;

    /* ===== Auditing ===== */
    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    /* ===== 도메인 행위 ===== */

    public static AuthError record(
            String requestId,
            OffsetDateTime occurredAt,
            OffsetDateTime receivedAt,
            String sourceService,
            String environment
    ) {
        return AuthError.builder()
                /* ===== 식별/추적 ===== */
                .requestId(requestId)

                /* ===== 발생/출처 ===== */
                .occurredAt(occurredAt)
                .receivedAt(receivedAt)
                .sourceService(sourceService)
                .environment(environment)

                /* ===== 에러 분류 기본값 ===== */
                .errorDomain("AUTH")
                .severity("ERROR")
                .status(AuthErrorStatus.NEW)
                .retryCount(0)

                /* ===== 기타 기본값 ===== */
                .dedupKey(requestId) // 지금 구조 기준: requestId 기준 멱등
                .build();
    }

    public void markProcessed() {
        this.status = AuthErrorStatus.PROCESSED;
        this.lastProcessedAt = OffsetDateTime.now();
        this.nextRetryAt = null;
    }

    public void markRetry() {
        this.status = AuthErrorStatus.RETRY;
        this.retryCount += 1;
        this.nextRetryAt = null;
    }

    public void markFailed(String reason) {
        this.status = AuthErrorStatus.FAILED;
        this.resolutionNote = reason;
        this.nextRetryAt = null;
    }

    public void markAnalysisRequested() {
        this.status = AuthErrorStatus.ANALYSIS_REQUESTED;
    }

    public void resolve(String note) {
        this.status = AuthErrorStatus.RESOLVED;
        this.resolutionNote = note;
        this.resolvedAt = OffsetDateTime.now();
        this.nextRetryAt = null;
    }
}
