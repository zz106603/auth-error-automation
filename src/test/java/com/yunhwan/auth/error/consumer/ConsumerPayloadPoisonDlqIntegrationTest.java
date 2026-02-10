package com.yunhwan.auth.error.consumer;

import com.yunhwan.auth.error.infra.messaging.rabbit.RabbitTopologyConfig;
import com.yunhwan.auth.error.testsupport.base.AbstractStubIntegrationTest;
import com.yunhwan.auth.error.testsupport.messaging.DuplicateDeliveryInjector;
import com.yunhwan.auth.error.testsupport.stub.StubDlqObserver;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("[TS-12] Consumer payload poison DLQ 통합 테스트")
class ConsumerPayloadPoisonDlqIntegrationTest extends AbstractStubIntegrationTest {

    @Autowired
    DuplicateDeliveryInjector injector;
    @Autowired
    StubDlqObserver dlqObserver;
    @Autowired
    JdbcTemplate jdbcTemplate;
    @Autowired
    AmqpAdmin amqpAdmin;
    @Autowired
    RabbitTemplate rabbitTemplate;

    @BeforeEach
    void setUp() {
        dlqObserver.reset();
        purgeQueue(RabbitTopologyConfig.Q_RECORDED);
        purgeQueue(RabbitTopologyConfig.DLQ_RECORDED);
        jdbcTemplate.update("delete from processed_message");
        jdbcTemplate.update("delete from outbox_message");
        jdbcTemplate.update("delete from auth_error_cluster_item");
        jdbcTemplate.update("delete from auth_error_analysis_result");
        jdbcTemplate.update("delete from auth_error_cluster");
        jdbcTemplate.update("delete from auth_error");
    }

    @Test
    @DisplayName("[TS-12] payload 파싱 실패는 즉시 DLQ + 무부작용")
    void payload_파싱_실패_시_즉시_DLQ_및_무부작용() {
        // Given
        long authErrorId = insertAuthError("REQ-POISON-" + UUID.randomUUID());
        long beforeProcessed = count("processed_message");
        long beforeAuthError = count("auth_error");
        long beforeOutbox = count("outbox_message");

        long outboxId = Math.abs(UUID.randomUUID().getMostSignificantBits());
        Map<String, Object> headers = new HashMap<>();
        headers.put("outboxId", outboxId);
        headers.put("eventType", RabbitTopologyConfig.RK_RECORDED);
        headers.put("aggregateType", "auth_error");

        // authErrorId 누락된 payload
        String invalidPayload = "{"
                + "\"requestId\":\"REQ-POISON\","
                + "\"occurredAt\":\"" + OffsetDateTime.now() + "\""
                + "}";

        // When
        injector.sendWithHeaders(
                RabbitTopologyConfig.EXCHANGE,
                RabbitTopologyConfig.RK_RECORDED,
                invalidPayload,
                headers
        );

        // Then: DLQ 적재
        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> {
                    drainDlqIfPresent();
                    assertThat(dlqObserver.count()).isEqualTo(1L);
                    assertThat(dlqObserver.lastOutboxId()).isEqualTo(outboxId);
                });

        // Then: 무부작용
        assertThat(count("processed_message")).isEqualTo(beforeProcessed);
        assertThat(count("auth_error")).isEqualTo(beforeAuthError);
        assertThat(count("outbox_message")).isEqualTo(beforeOutbox);

        String status = jdbcTemplate.queryForObject(
                "select status from auth_error where id = ?",
                String.class,
                authErrorId
        );
        assertThat(status).isEqualTo("NEW");
    }

    private long insertAuthError(String requestId) {
        return jdbcTemplate.queryForObject(
                "insert into auth_error (request_id, source_service, environment) values (?, ?, ?) returning id",
                Long.class,
                requestId,
                "test-service",
                "test"
        );
    }

    private long count(String table) {
        return jdbcTemplate.queryForObject("select count(*) from " + table, Long.class);
    }

    private void drainDlqIfPresent() {
        if (dlqObserver.count() > 0) {
            return;
        }
        Message msg = rabbitTemplate.receive(RabbitTopologyConfig.DLQ_RECORDED);
        if (msg == null) {
            return;
        }
        Object header = msg.getMessageProperties().getHeaders().get("outboxId");
        Long outboxId = null;
        if (header instanceof Number n) {
            outboxId = n.longValue();
        } else if (header != null) {
            try {
                outboxId = Long.parseLong(String.valueOf(header));
            } catch (NumberFormatException ignore) {
                outboxId = null;
            }
        }
        String payload = new String(msg.getBody());
        dlqObserver.onDlq(outboxId, payload);
    }

    private void purgeQueue(String queue) {
        if (amqpAdmin != null) {
            amqpAdmin.purgeQueue(queue, true);
        }
    }
}
