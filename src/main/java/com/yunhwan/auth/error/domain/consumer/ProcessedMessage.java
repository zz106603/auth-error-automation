package com.yunhwan.auth.error.domain.consumer;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        name = "processed_message",
        indexes = {
                @Index(
                        name = "idx_processed_message_retry_ready",
                        columnList = "status, next_retry_at"
                )
        }
)
public class ProcessedMessage {

    @Id
    @Column(name = "outbox_id")
    private Long outboxId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ProcessedStatus status; // "PROCESSING" | "DONE"

    @Column(name = "lease_until")
    private OffsetDateTime leaseUntil;

    @Column(name = "processed_at")
    private OffsetDateTime processedAt; // DONE 확정 시점

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Column(name = "next_retry_at")
    private OffsetDateTime nextRetryAt;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "dead_at")
    private OffsetDateTime deadAt;

}
