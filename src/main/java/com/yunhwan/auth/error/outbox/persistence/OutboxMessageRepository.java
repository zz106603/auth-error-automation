package com.yunhwan.auth.error.outbox.persistence;

import com.yunhwan.auth.error.domain.outbox.OutboxMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

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
    @Transactional
    @Query(value = """
        UPDATE outbox_message
        SET next_retry_at = :nextRetryAt,
            updated_at = now()
        WHERE id = :id
        """, nativeQuery = true)
    int setNextRetryAt(@Param("id") Long id, @Param("nextRetryAt") OffsetDateTime nextRetryAt);

    // === 성공 ===
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(value = """
        update outbox_message
           set status = 'PUBLISHED',
               processing_owner = null,
               processing_started_at = null,
               last_error = null,
               next_retry_at = null,
               updated_at = :now
         where id = :id
        """, nativeQuery = true)
    int markPublished(@Param("id") long id, @Param("now") OffsetDateTime now);

    // === 재시도 ===
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(value = """
        update outbox_message
           set status = 'PENDING',
               processing_owner = null,
               processing_started_at = null,
               retry_count = :retryCount,
               next_retry_at = :nextRetryAt,
               last_error = :lastError,
               updated_at = :now
         where id = :id
        """, nativeQuery = true)
    int markForRetry(
            @Param("id") long id,
            @Param("retryCount") int retryCount,
            @Param("nextRetryAt") OffsetDateTime nextRetryAt,
            @Param("lastError") String lastError,
            @Param("now") OffsetDateTime now
    );

    // === 포기 ===
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(value = """
        update outbox_message
           set status = 'DEAD',
               processing_owner = null,
               processing_started_at = null,
               retry_count = :retryCount,
               next_retry_at = null,
               last_error = :lastError,
               updated_at = :now
         where id = :id
        """, nativeQuery = true)
    int markDead(
            @Param("id") long id,
            @Param("retryCount") int retryCount,
            @Param("lastError") String lastError,
            @Param("now") OffsetDateTime now
    );

    @Query(value = """
    select *
      from outbox_message
     where status = 'PROCESSING'
       and processing_started_at is not null
       and processing_started_at <= :staleBefore
     order by processing_started_at asc
     limit :batchSize
     for update skip locked
    """, nativeQuery = true)
    List<OutboxMessage> pickStaleProcessing(
            @Param("staleBefore") OffsetDateTime staleBefore,
            @Param("batchSize") int batchSize
    );

    // 기본 리퍼
    @Query(value = """
    select *
      from outbox_message
     where status = 'PROCESSING'
       and processing_owner = :owner
       and processing_started_at is not null
       and processing_started_at <= :staleBefore
     order by processing_started_at asc
     limit :batchSize
     for update skip locked
    """, nativeQuery = true)
    List<OutboxMessage> pickStaleProcessingByOwner(
            @Param("owner") String owner,
            @Param("staleBefore") OffsetDateTime staleBefore,
            @Param("batchSize") int batchSize
    );

}
