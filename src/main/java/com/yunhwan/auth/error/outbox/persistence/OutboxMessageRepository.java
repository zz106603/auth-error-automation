package com.yunhwan.auth.error.outbox.persistence;

import com.yunhwan.auth.error.domain.outbox.OutboxMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface OutboxMessageRepository extends JpaRepository<OutboxMessage, Long> {

    Optional<OutboxMessage> findByIdempotencyKey(String idempotencyKey);

    boolean existsByIdempotencyKey(String idempotencyKey);

    /**
     *
     * Outbox insert
     * UpsertReturning 방식
     */
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

    /**
     *
     * Outbox select
     * PENDING 값 조회
     */
    @Query(value = """
        WITH picked AS (
          SELECT id
          FROM outbox_message
          WHERE status = 'PENDING'
            AND (next_retry_at IS NULL OR next_retry_at <= now())
          ORDER BY COALESCE(next_retry_at, created_at), created_at
          LIMIT :batchSize
          FOR UPDATE SKIP LOCKED
        )
        UPDATE outbox_message o
        SET status = 'PROCESSING',
            processing_owner = :owner,
            processing_started_at = now(),
            updated_at = now()
        FROM picked
        WHERE o.id = picked.id
        RETURNING o.*
        """, nativeQuery = true)
    List<OutboxMessage> claimBatch(
            @Param("batchSize") int batchSize,
            @Param("owner") String owner
    );

    // 테스트용: next_retry_at 세팅 (운영 코드에서도 retry 처리 시 쓰게 됨)
    @Modifying
    @Query(value = """
        UPDATE outbox_message
        SET next_retry_at = :nextRetryAt,
            updated_at = now()
        WHERE id = :id
        """, nativeQuery = true)
    int setNextRetryAt(@Param("id") Long id, @Param("nextRetryAt") OffsetDateTime nextRetryAt);

}
