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
        name = "dead_letter_message",
        uniqueConstraints = {
                @UniqueConstraint(name = "ux_dead_letter_message_dedupe_key", columnNames = "dedupe_key")
        }
)
public class DeadLetterMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "dedupe_key", nullable = false, length = 128)
    private String dedupeKey;

    @Column(name = "dlq_queue", nullable = false, length = 200)
    private String dlqQueue;

    @Column(name = "source_queue", length = 200)
    private String sourceQueue;

    @Column(name = "source_exchange", length = 200)
    private String sourceExchange;

    @Column(name = "source_routing_key", length = 200)
    private String sourceRoutingKey;

    @Column(name = "dead_letter_exchange", length = 200)
    private String deadLetterExchange;

    @Column(name = "dead_letter_routing_key", length = 200)
    private String deadLetterRoutingKey;

    @Column(name = "outbox_id")
    private Long outboxId;

    @Column(name = "event_type", length = 200)
    private String eventType;

    @Column(name = "aggregate_type", length = 100)
    private String aggregateType;

    @Column(name = "payload", nullable = false, columnDefinition = "text")
    private String payload;

    @Column(name = "payload_hash", nullable = false, length = 64)
    private String payloadHash;

    @Column(name = "payload_size_bytes", nullable = false)
    private int payloadSizeBytes;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason_code", nullable = false, length = 80)
    private DeadLetterReasonCode reasonCode;

    @Column(name = "broker_death_reason", length = 80)
    private String brokerDeathReason;

    @Type(JsonBinaryType.class)
    @Column(name = "x_death", columnDefinition = "jsonb")
    private String xDeath;

    @Column(name = "retry_count")
    private Integer retryCount;

    @Column(name = "processed_message_status_at_arrival", length = 30)
    private String processedMessageStatusAtArrival;

    @Column(name = "outbox_status_at_arrival", length = 30)
    private String outboxStatusAtArrival;

    @Column(name = "first_seen_at", nullable = false, updatable = false)
    private OffsetDateTime firstSeenAt;

    @Column(name = "last_seen_at", nullable = false)
    private OffsetDateTime lastSeenAt;

    @Column(name = "delivery_count", nullable = false)
    private int deliveryCount;

    @Enumerated(EnumType.STRING)
    @Column(name = "replay_status", nullable = false, length = 30)
    private ReplayStatus replayStatus;

    @Column(name = "replay_requested_at")
    private OffsetDateTime replayRequestedAt;

    @Column(name = "replay_started_at")
    private OffsetDateTime replayStartedAt;

    @Column(name = "replayed_at")
    private OffsetDateTime replayedAt;

    @Column(name = "replay_failed_at")
    private OffsetDateTime replayFailedAt;

    @Column(name = "replay_failure_reason", columnDefinition = "text")
    private String replayFailureReason;

    @Column(name = "operator_note", columnDefinition = "text")
    private String operatorNote;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
