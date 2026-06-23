package com.yunhwan.auth.error.infra.persistence.jpa;

import com.yunhwan.auth.error.domain.consumer.RetryPublishRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

public interface RetryPublishRequestJpaRepository extends JpaRepository<RetryPublishRequest, Long> {

    @Query(value = """
    insert into retry_publish_request
      (source_outbox_id, event_type, aggregate_type, payload, retry_count, next_retry_at, last_error, next_publish_at, updated_at)
    values
      (:sourceOutboxId, :eventType, :aggregateType, cast(:payload as jsonb), :retryCount, :nextRetryAt, :lastError, :now, :now)
    on conflict (source_outbox_id, retry_count)
    do update set updated_at = :now
    returning *
    """, nativeQuery = true)
    RetryPublishRequest enqueue(
            @Param("sourceOutboxId") long sourceOutboxId,
            @Param("eventType") String eventType,
            @Param("aggregateType") String aggregateType,
            @Param("payload") String payload,
            @Param("retryCount") int retryCount,
            @Param("nextRetryAt") OffsetDateTime nextRetryAt,
            @Param("lastError") String lastError,
            @Param("now") OffsetDateTime now
    );

    @Query(value = """
    with picked as (
      select id
        from retry_publish_request
       where status = 'PENDING'
         and (next_publish_at is null or next_publish_at <= :now)
       order by coalesce(next_publish_at, created_at), created_at
       limit :batchSize
       for update skip locked
    )
    update retry_publish_request r
       set status = 'PROCESSING',
           processing_owner = :owner,
           processing_started_at = :now,
           updated_at = :now
      from picked
     where r.id = picked.id
    returning r.*
    """, nativeQuery = true)
    List<RetryPublishRequest> claimBatch(
            @Param("batchSize") int batchSize,
            @Param("owner") String owner,
            @Param("now") OffsetDateTime now
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(value = """
    update retry_publish_request
       set status = 'PUBLISHED',
           processing_owner = null,
           processing_started_at = null,
           published_at = :now,
           next_publish_at = null,
           last_publish_error = null,
           updated_at = :now
     where id = :id
       and status = 'PROCESSING'
       and processing_owner = :owner
    """, nativeQuery = true)
    int markPublished(@Param("id") long id, @Param("owner") String owner, @Param("now") OffsetDateTime now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(value = """
    update retry_publish_request
       set status = 'PENDING',
           processing_owner = null,
           processing_started_at = null,
           publish_retry_count = :publishRetryCount,
           next_publish_at = :nextPublishAt,
           last_publish_error = :lastError,
           updated_at = :now
     where id = :id
       and status = 'PROCESSING'
       and processing_owner = :owner
    """, nativeQuery = true)
    int markForRetry(
            @Param("id") long id,
            @Param("owner") String owner,
            @Param("publishRetryCount") int publishRetryCount,
            @Param("nextPublishAt") OffsetDateTime nextPublishAt,
            @Param("lastError") String lastError,
            @Param("now") OffsetDateTime now
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(value = """
    update retry_publish_request
       set status = 'DEAD',
           processing_owner = null,
           processing_started_at = null,
           publish_retry_count = :publishRetryCount,
           next_publish_at = null,
           last_publish_error = :lastError,
           updated_at = :now
     where id = :id
       and status = 'PROCESSING'
       and processing_owner = :owner
    """, nativeQuery = true)
    int markDead(
            @Param("id") long id,
            @Param("owner") String owner,
            @Param("publishRetryCount") int publishRetryCount,
            @Param("lastError") String lastError,
            @Param("now") OffsetDateTime now
    );
}
