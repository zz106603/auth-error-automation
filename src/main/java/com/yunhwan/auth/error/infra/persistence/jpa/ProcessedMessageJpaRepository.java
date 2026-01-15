package com.yunhwan.auth.error.infra.persistence.jpa;

import com.yunhwan.auth.error.domain.consumer.ProcessedMessage;
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
     * 선점(claim):
     * - row가 없으면 새로 만들어 PROCESSING + lease 설정
     * - row가 있는데 DONE이면 선점 실패(0)
     * - row가 PROCESSING이라도 lease가 만료됐으면 lease 갱신하며 선점 성공(1)
     * - row가 PROCESSING인데 lease가 아직 유효하면 선점 실패(0)
     */
    @Transactional
    @Modifying
    @Query(value = """
        insert into processed_message(outbox_id, status, lease_until, updated_at)
        values (:outboxId, :status, :leaseUntil, :now)
        on conflict (outbox_id) do update
           set status = :status,
               lease_until = :leaseUntil,
               updated_at = :now
         where processed_message.status = :status
           and (processed_message.lease_until is null or processed_message.lease_until <= :now)
        """, nativeQuery = true)
    int claimProcessing(@Param("outboxId") long outboxId,
                        @Param("now") OffsetDateTime now,
                        @Param("leaseUntil") OffsetDateTime leaseUntil,
                        @Param("status") String status);

    /**
     * 완료 확정(DONE):
     * - PROCESSING인 row만 DONE으로 변경 (이미 DONE이면 0)
     */
    @Transactional
    @Modifying
    @Query(value = """
        update processed_message
           set status = :doneStatus,
               processed_at = :now,
               lease_until = null,
               updated_at = :now
         where outbox_id = :outboxId
           and status = :processingStatus
        """, nativeQuery = true)
    int markDone(@Param("outboxId") long outboxId,
                 @Param("now") OffsetDateTime now,
                 @Param("doneStatus") String doneStatus,
                 @Param("processingStatus") String processingStatus);

    @Transactional
    @Modifying
    @Query(value = """
    update processed_message
       set lease_until = :now,
           updated_at = :now
     where outbox_id = :outboxId
       and status = :status
    """, nativeQuery = true)
    int releaseLeaseForRetry(@Param("outboxId") long outboxId,
                             @Param("now") OffsetDateTime now,
                             @Param("status") String status);
}
