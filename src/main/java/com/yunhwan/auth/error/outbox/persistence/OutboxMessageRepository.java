package com.yunhwan.auth.error.outbox.persistence;

import com.yunhwan.auth.error.domain.outbox.OutboxMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface OutboxMessageRepository extends JpaRepository<OutboxMessage, Long> {

    Optional<OutboxMessage> findByIdempotencyKey(String idempotencyKey);

    boolean existsByIdempotencyKey(String idempotencyKey);

    @Query(value = """
    INSERT INTO outbox_message
      (aggregate_type, aggregate_id, event_type, payload, idempotency_key)
    VALUES
      (:aggregateType, :aggregateId, :eventType, CAST(:payloadJson AS jsonb), :idempotencyKey)
    ON CONFLICT (idempotency_key)
    DO UPDATE SET updated_at = now()
    RETURNING *
    """, nativeQuery = true)
    OutboxMessage upsertReturning(
            @Param("aggregateType") String aggregateType,
            @Param("aggregateId") String aggregateId,
            @Param("eventType") String eventType,
            @Param("payloadJson") String payloadJson,
            @Param("idempotencyKey") String idempotencyKey
    );
}
