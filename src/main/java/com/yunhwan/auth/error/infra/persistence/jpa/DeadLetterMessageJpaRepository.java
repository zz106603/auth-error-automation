package com.yunhwan.auth.error.infra.persistence.jpa;

import com.yunhwan.auth.error.domain.consumer.DeadLetterMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Optional;

public interface DeadLetterMessageJpaRepository extends JpaRepository<DeadLetterMessage, Long> {

    Optional<DeadLetterMessage> findByDedupeKey(String dedupeKey);

    @Query(value = """
    insert into dead_letter_message
      (dedupe_key, dlq_queue, source_queue, source_exchange, source_routing_key,
       dead_letter_exchange, dead_letter_routing_key, outbox_id, event_type, aggregate_type,
       payload, payload_hash, payload_size_bytes, reason_code, broker_death_reason, x_death,
       retry_count, processed_message_status_at_arrival, outbox_status_at_arrival,
       first_seen_at, last_seen_at, delivery_count, replay_status, updated_at)
    values
      (:dedupeKey, :dlqQueue, :sourceQueue, :sourceExchange, :sourceRoutingKey,
       :deadLetterExchange, :deadLetterRoutingKey, :outboxId, :eventType, :aggregateType,
       :payload, :payloadHash, :payloadSizeBytes, :reasonCode, :brokerDeathReason, cast(:xDeath as jsonb),
       :retryCount, :processedMessageStatusAtArrival, :outboxStatusAtArrival,
       :now, :now, 1, :replayStatus, :now)
    on conflict (dedupe_key)
    do update set
       last_seen_at = excluded.last_seen_at,
       delivery_count = dead_letter_message.delivery_count + 1,
       reason_code = excluded.reason_code,
       broker_death_reason = excluded.broker_death_reason,
       x_death = excluded.x_death,
       retry_count = excluded.retry_count,
       processed_message_status_at_arrival = excluded.processed_message_status_at_arrival,
       outbox_status_at_arrival = excluded.outbox_status_at_arrival,
       updated_at = excluded.updated_at
    returning *
    """, nativeQuery = true)
    DeadLetterMessage upsert(
            @Param("dedupeKey") String dedupeKey,
            @Param("dlqQueue") String dlqQueue,
            @Param("sourceQueue") String sourceQueue,
            @Param("sourceExchange") String sourceExchange,
            @Param("sourceRoutingKey") String sourceRoutingKey,
            @Param("deadLetterExchange") String deadLetterExchange,
            @Param("deadLetterRoutingKey") String deadLetterRoutingKey,
            @Param("outboxId") Long outboxId,
            @Param("eventType") String eventType,
            @Param("aggregateType") String aggregateType,
            @Param("payload") String payload,
            @Param("payloadHash") String payloadHash,
            @Param("payloadSizeBytes") int payloadSizeBytes,
            @Param("reasonCode") String reasonCode,
            @Param("brokerDeathReason") String brokerDeathReason,
            @Param("xDeath") String xDeath,
            @Param("retryCount") Integer retryCount,
            @Param("processedMessageStatusAtArrival") String processedMessageStatusAtArrival,
            @Param("outboxStatusAtArrival") String outboxStatusAtArrival,
            @Param("replayStatus") String replayStatus,
            @Param("now") OffsetDateTime now
    );
}
