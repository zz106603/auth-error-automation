package com.yunhwan.auth.error.infra.persistence.jpa;

import com.yunhwan.auth.error.domain.consumer.ProcessedMessage;
import com.yunhwan.auth.error.domain.consumer.ProcessedStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

public interface ProcessedMessageJpaRepository extends JpaRepository<ProcessedMessage, Long> {

    long count();
    boolean existsById(Long id);
    boolean existsByOutboxId(Long outboxId);

    /**
     *  1) row 없으면 PENDING 생성
     */
    @Transactional
    @Modifying
    @Query(value = """
    insert into processed_message (outbox_id, status, retry_count, next_retry_at, updated_at)
    values (:outboxId, 'PENDING', 0, null, :now)
    on conflict (outbox_id) do nothing
    """, nativeQuery = true)
    void ensureRowExists(@Param("outboxId") long outboxId,
                         @Param("now") OffsetDateTime now);

    /**
     *
     * 2) PENDING/RETRY_WAIT 이면서 lease 만료면 PROCESSING 선점
     *    (RabbitMQ TTL이 지연을 보장하지만, DB 수준에서도 next_retry_at을 확인하여 안정성을 높임)
     */
    @Transactional
    @Modifying
    @Query(value = """
    update processed_message
       set status = 'PROCESSING',
           lease_until = :leaseUntil,
           updated_at = :now
     where outbox_id = :outboxId
       and (
            status = 'PENDING'
            or (status = 'RETRY_WAIT'
                and next_retry_at is not null
                and next_retry_at <= :now)
            or (status = 'PROCESSING'
                and lease_until is not null
                and lease_until <= :now)
       )
    """, nativeQuery = true)
    int claimProcessingUpdate(@Param("outboxId") long outboxId,
                              @Param("now") OffsetDateTime now,
                              @Param("leaseUntil") OffsetDateTime leaseUntil);

    /**
     * 성공 확정: PROCESSING -> DONE
     */
    @Transactional
    @Modifying
    @Query(value = """
        update processed_message
           set status = 'DONE',
               lease_until = null,
               next_retry_at = null,
               last_error = null,
               processed_at = :now,
               updated_at = :now
         where outbox_id = :outboxId
           and status = 'PROCESSING'
        """, nativeQuery = true)
    int markDone(@Param("outboxId") long outboxId,
                 @Param("now") OffsetDateTime now);

    /**
     * 재시도 대기 전환: PROCESSING -> RETRY_WAIT
     */
    @Transactional
    @Modifying
    @Query(value = """
        update processed_message
           set status = 'RETRY_WAIT',
               lease_until = null,
               retry_count = :nextRetryCount,
               next_retry_at = :nextRetryAt,
               last_error = :lastError,
               updated_at = :now
         where outbox_id = :outboxId
           and status = 'PROCESSING'
        """, nativeQuery = true)
    int markRetryWait(@Param("outboxId") long outboxId,
                      @Param("now") OffsetDateTime now,
                      @Param("nextRetryAt") OffsetDateTime nextRetryAt,
                      @Param("nextRetryCount") int nextRetryCount,
                      @Param("lastError") String lastError);

    @Transactional
    @Modifying
    @Query(value = """
    update processed_message
       set status = 'DEAD',
           lease_until = null,
           next_retry_at = null,
           last_error = :lastError,
           dead_at = :now,
           updated_at = :now
     where outbox_id = :outboxId
       and status = 'PROCESSING'
    """, nativeQuery = true)
    int markDead(@Param("outboxId") long outboxId,
                 @Param("now") OffsetDateTime now,
                 @Param("lastError") String lastError);

    @Query(value = """
    select status
      from processed_message
     where outbox_id = :outboxId
    """, nativeQuery = true)
    ProcessedStatus findStatusByOutboxId(@Param("outboxId") long outboxId);
}
