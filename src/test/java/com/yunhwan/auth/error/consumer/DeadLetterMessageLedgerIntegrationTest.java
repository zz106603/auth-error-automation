package com.yunhwan.auth.error.consumer;

import com.yunhwan.auth.error.infra.messaging.rabbit.RabbitTopologyConfig;
import com.yunhwan.auth.error.testsupport.base.AbstractStubIntegrationTest;
import com.yunhwan.auth.error.testsupport.messaging.DuplicateDeliveryInjector;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("[02] DLQ ledger 통합 테스트")
class DeadLetterMessageLedgerIntegrationTest extends AbstractStubIntegrationTest {

    @Autowired
    DuplicateDeliveryInjector injector;
    @Autowired
    JdbcTemplate jdbcTemplate;
    @Autowired
    AmqpAdmin amqpAdmin;

    @BeforeEach
    void setUp() {
        purgeQueue(RabbitTopologyConfig.Q_RECORDED);
        purgeQueue(RabbitTopologyConfig.DLQ_RECORDED);
        jdbcTemplate.update("delete from dead_letter_message");
        jdbcTemplate.update("delete from retry_publish_request");
        jdbcTemplate.update("delete from processed_message");
        jdbcTemplate.update("delete from outbox_message");
        jdbcTemplate.update("delete from auth_error_cluster_item");
        jdbcTemplate.update("delete from auth_error_analysis_result");
        jdbcTemplate.update("delete from auth_error_cluster");
        jdbcTemplate.update("delete from auth_error");
    }

    @Test
    @DisplayName("DLQ 도착 메시지는 dead_letter_message 원장에 저장된다")
    void dlq_도착_메시지는_원장에_저장된다() {
        long outboxId = Math.abs(UUID.randomUUID().getMostSignificantBits());
        Map<String, Object> headers = validHeaders(outboxId);

        injector.sendWithHeaders(
                RabbitTopologyConfig.EXCHANGE,
                RabbitTopologyConfig.RK_RECORDED,
                "{\"requestId\":\"REQ-POISON\"}",
                headers
        );

        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> {
                    assertThat(count()).isEqualTo(1L);
                    Map<String, Object> row = ledgerRow(outboxId);
                    assertThat(row.get("outbox_id")).isEqualTo(outboxId);
                    assertThat(row.get("reason_code")).isEqualTo("PAYLOAD_MISSING_AUTH_ERROR_ID");
                    assertThat(row.get("replay_status")).isEqualTo("NOT_REPLAYABLE");
                    assertThat(row.get("payload_hash")).isNotNull();
                    assertThat(row.get("payload_size_bytes")).isEqualTo(26);
                    assertThat(row.get("delivery_count")).isEqualTo(1);
                });
    }

    @Test
    @DisplayName("같은 DLQ 메시지가 중복 도착하면 기존 원장을 갱신한다")
    void 중복_DLQ_도착은_기존_원장을_갱신한다() {
        long outboxId = Math.abs(UUID.randomUUID().getMostSignificantBits());
        Map<String, Object> headers = validHeaders(outboxId);
        String payload = "{\"requestId\":\"REQ-DUP\"}";

        injector.sendWithHeaders(RabbitTopologyConfig.EXCHANGE, RabbitTopologyConfig.RK_RECORDED, payload, headers);
        injector.sendWithHeaders(RabbitTopologyConfig.EXCHANGE, RabbitTopologyConfig.RK_RECORDED, payload, headers);

        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> {
                    assertThat(count()).isEqualTo(1L);
                    assertThat(ledgerRow(outboxId).get("delivery_count")).isEqualTo(2);
                });
    }

    @Test
    @DisplayName("헤더 계약 위반 reason code는 안정적으로 분류된다")
    void 헤더_계약_위반_reason_code를_분류한다() {
        long outboxId = Math.abs(UUID.randomUUID().getMostSignificantBits());
        Map<String, Object> headers = new HashMap<>();
        headers.put("outboxId", outboxId);
        headers.put("aggregateType", "auth_error");

        injector.sendWithHeaders(
                RabbitTopologyConfig.EXCHANGE,
                RabbitTopologyConfig.RK_RECORDED,
                "{\"authErrorId\":123}",
                headers
        );

        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> {
                    assertThat(count()).isEqualTo(1L);
                    Map<String, Object> row = ledgerRow(outboxId);
                    assertThat(row.get("reason_code")).isEqualTo("CONTRACT_MISSING_EVENT_TYPE");
                    assertThat(row.get("replay_status")).isEqualTo("NOT_REPLAYABLE");
                });
    }

    private Map<String, Object> validHeaders(long outboxId) {
        Map<String, Object> headers = new HashMap<>();
        headers.put("outboxId", outboxId);
        headers.put("eventType", RabbitTopologyConfig.RK_RECORDED);
        headers.put("aggregateType", "auth_error");
        return headers;
    }

    private long count() {
        return Objects.requireNonNull(jdbcTemplate.queryForObject(
                "select count(*) from dead_letter_message",
                Long.class
        ));
    }

    private Map<String, Object> ledgerRow(long outboxId) {
        return jdbcTemplate.queryForMap(
                """
                select outbox_id, reason_code, replay_status, payload_hash, payload_size_bytes, delivery_count
                  from dead_letter_message
                 where outbox_id = ?
                """,
                outboxId
        );
    }

    private void purgeQueue(String queue) {
        if (amqpAdmin != null) {
            amqpAdmin.purgeQueue(queue, true);
        }
    }
}
