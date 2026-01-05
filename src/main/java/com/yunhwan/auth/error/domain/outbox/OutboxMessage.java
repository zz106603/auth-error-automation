package com.yunhwan.auth.error.domain.outbox;

import com.vladmihalcea.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Type;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.OffsetDateTime;

@Entity
@Table(
        name = "outbox_message",
        indexes = {
                @Index(name = "ix_outbox_polling", columnList = "status,next_retry_at,created_at"),
                @Index(name = "ix_outbox_aggregate", columnList = "aggregate_type,aggregate_id")
        },
        uniqueConstraints = {
                @UniqueConstraint(name = "ux_outbox_idempotency_key", columnNames = "idempotency_key")
        }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
@Getter
public class OutboxMessage {

    private static final int DEFAULT_MAX_RETRIES = 10;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "aggregate_type", nullable = false, length = 100)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, length = 100)
    private String aggregateId;

    @Column(name = "event_type", nullable = false, length = 200)
    private String eventType;

    /**
     * PostgreSQL jsonb 컬럼.
     * MVP에서는 String(JSON)으로 들고 가는 게 가장 단순.
     */
    @Type(JsonBinaryType.class)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(name = "idempotency_key", nullable = false, length = 200)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private OutboxStatus status = OutboxStatus.PENDING;

    @Column(name = "processing_owner", length = 100)
    private String processingOwner;

    @Column(name = "processing_started_at")
    private OffsetDateTime processingStartedAt;

    @Column(name = "retry_count", nullable = false)
    private int retryCount = 0;

    @Column(name = "max_retries", nullable = false)
    private int maxRetries = DEFAULT_MAX_RETRIES;

    @Column(name = "next_retry_at")
    private OffsetDateTime nextRetryAt;

    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "published_at")
    private OffsetDateTime publishedAt;

    // ---- Factory (권장) ----
    public static OutboxMessage pending(
            String aggregateType,
            String aggregateId,
            String eventType,
            String payloadJson,
            String idempotencyKey
    ) {
        OutboxMessage m = new OutboxMessage();
        m.aggregateType = aggregateType;
        m.aggregateId = aggregateId;
        m.eventType = eventType;
        m.payload = payloadJson;
        m.idempotencyKey = idempotencyKey;
        m.nextRetryAt = null;
        return m;
    }

    // ---- 상태 전이 메서드 (Publisher 단계에서 사용) ----
    public void markPublished() {
        this.status = OutboxStatus.PUBLISHED;
        this.publishedAt = OffsetDateTime.now(java.time.ZoneOffset.UTC);
        this.lastError = null;
    }

    public void markForRetry(String errorMessage, OffsetDateTime nextRetryAt) {
        this.status = OutboxStatus.PENDING;
        this.retryCount += 1;
        this.lastError = errorMessage;
        this.nextRetryAt = nextRetryAt;
        this.processingOwner = null;
        this.processingStartedAt = null;
    }

    public void markDead(String errorMessage) {
        this.status = OutboxStatus.DEAD;
        this.lastError = errorMessage;
        this.processingOwner = null;
        this.processingStartedAt = null;
    }
}
