package com.yunhwan.auth.error.domain.consumer;

import com.vladmihalcea.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Type;

import java.time.OffsetDateTime;

@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        name = "retry_publish_request",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "ux_retry_publish_request_outbox_retry",
                        columnNames = {"source_outbox_id", "retry_count"}
                )
        }
)
public class RetryPublishRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "source_outbox_id", nullable = false)
    private Long sourceOutboxId;

    @Column(name = "event_type", nullable = false, length = 200)
    private String eventType;

    @Column(name = "aggregate_type", nullable = false, length = 100)
    private String aggregateType;

    @Type(JsonBinaryType.class)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Column(name = "next_retry_at", nullable = false)
    private OffsetDateTime nextRetryAt;

    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private RetryPublishStatus status = RetryPublishStatus.PENDING;

    @Column(name = "publish_retry_count", nullable = false)
    private int publishRetryCount;

    @Column(name = "processing_owner", length = 100)
    private String processingOwner;

    @Column(name = "processing_started_at")
    private OffsetDateTime processingStartedAt;

    @Column(name = "published_at")
    private OffsetDateTime publishedAt;

    @Column(name = "next_publish_at")
    private OffsetDateTime nextPublishAt;

    @Column(name = "last_publish_error", columnDefinition = "text")
    private String lastPublishError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
