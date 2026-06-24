package com.yunhwan.auth.error.infra.persistence.adapter;

import com.yunhwan.auth.error.usecase.consumer.dto.DeadLetterSourceSnapshot;
import com.yunhwan.auth.error.usecase.consumer.port.DeadLetterSourceLookup;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
@RequiredArgsConstructor
public class DeadLetterSourceLookupAdapter implements DeadLetterSourceLookup {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public DeadLetterSourceSnapshot findSnapshot(Long outboxId) {
        if (outboxId == null) {
            return new DeadLetterSourceSnapshot(null, null, null, null);
        }

        ProcessedSnapshot processed = findProcessed(outboxId);
        String outboxStatus = findOutboxStatus(outboxId);
        return new DeadLetterSourceSnapshot(
                processed.status(),
                processed.lastError(),
                processed.retryCount(),
                outboxStatus
        );
    }

    private ProcessedSnapshot findProcessed(Long outboxId) {
        List<ProcessedSnapshot> rows = jdbcTemplate.query(
                """
                select status, last_error, retry_count
                  from processed_message
                 where outbox_id = ?
                """,
                (rs, rowNum) -> new ProcessedSnapshot(
                        rs.getString("status"),
                        rs.getString("last_error"),
                        (Integer) rs.getObject("retry_count")
                ),
                outboxId
        );
        if (rows.isEmpty()) {
            return new ProcessedSnapshot(null, null, null);
        }
        return rows.getFirst();
    }

    private String findOutboxStatus(Long outboxId) {
        List<String> rows = jdbcTemplate.query(
                """
                select status
                  from outbox_message
                 where id = ?
                """,
                (rs, rowNum) -> rs.getString("status"),
                outboxId
        );
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private record ProcessedSnapshot(String status, String lastError, Integer retryCount) {
    }
}
