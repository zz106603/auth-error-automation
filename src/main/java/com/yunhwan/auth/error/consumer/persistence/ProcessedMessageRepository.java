package com.yunhwan.auth.error.consumer.persistence;

import com.yunhwan.auth.error.domain.consumer.ProcessedMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface ProcessedMessageRepository extends JpaRepository<ProcessedMessage, Long> {

    long count();
    boolean existsById(Long id);

    /**
     * outbox_id가 이미 있으면 아무것도 하지 않고 0을 반환.
     * 처음이면 insert 되고 1을 반환.
     */
    @Transactional
    @Modifying
    @Query(value = """
        insert into processed_message(outbox_id)
        values (:outboxId)
        on conflict (outbox_id) do nothing
        """, nativeQuery = true)
    int insertIgnore(@Param("outboxId") long outboxId);
}
