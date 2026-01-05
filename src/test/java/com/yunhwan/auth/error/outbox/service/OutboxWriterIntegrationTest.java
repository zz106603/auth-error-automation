package com.yunhwan.auth.error.outbox.service;

import com.yunhwan.auth.error.outbox.dto.OutboxEnqueueCommand;
import com.yunhwan.auth.error.outbox.persistence.OutboxMessageRepository;
import com.yunhwan.auth.error.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

class OutboxWriterIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    OutboxWriter outboxWriter;

    @Autowired
    OutboxMessageRepository outboxMessageRepository;

    @BeforeEach
    void setUp() {
        outboxMessageRepository.deleteAll();
    }

    @Test
    void enqueue_inserts_one_row() {
        // given
        OutboxEnqueueCommand cmd = createCommand("REQ-1", "{\"hello\":\"world\"}");

        // when
        var saved = outboxWriter.enqueue(cmd);

        // then
        assertThat(saved.getId()).isNotNull();
        assertThat(outboxMessageRepository.count()).isEqualTo(1);

        var found = outboxMessageRepository.findByIdempotencyKey(cmd.idempotencyKey());
        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(saved.getId());
        assertThat(found.get().getStatus().name()).isEqualTo("PENDING");
    }

    @Test
    void enqueue_is_idempotent_by_idempotency_key() {
        // given
        OutboxEnqueueCommand cmd = createCommand("REQ-2", "{\"x\":1}");

        // when
        var first = outboxWriter.enqueue(cmd);
        var second = outboxWriter.enqueue(cmd);

        // then
        assertThat(outboxMessageRepository.count()).isEqualTo(1);
        assertThat(second.getId()).isEqualTo(first.getId());
    }

    private OutboxEnqueueCommand createCommand(String reqId, String payload) {
        return new OutboxEnqueueCommand(
                "AUTH_ERROR",
                reqId,
                "AUTH_ERROR_DETECTED_V1",
                payload,
                "AUTH_ERROR:" + reqId + ":AUTH_ERROR_DETECTED_V1"
        );
    }
}
