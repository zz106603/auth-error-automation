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
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("[TS-10] Consumer retry gate 통합 테스트")
class ConsumerRetryGateIntegrationTest extends AbstractStubIntegrationTest {

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
        jdbcTemplate.update("delete from processed_message");
        jdbcTemplate.update("delete from outbox_message");
        jdbcTemplate.update("delete from auth_error_cluster_item");
        jdbcTemplate.update("delete from auth_error_analysis_result");
        jdbcTemplate.update("delete from auth_error_cluster");
        jdbcTemplate.update("delete from auth_error");
    }

    @Test
    @DisplayName("[TS-10] next_retry_at 이전에는 처리 차단, 이후에는 처리 허용")
    void next_retry_at_기준으로_처리_차단과_허용을_검증한다() {
        // Given
        long authErrorId = insertAuthError("REQ-GATE-" + UUID.randomUUID());
        long outboxId = Math.abs(UUID.randomUUID().getMostSignificantBits());

        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime future = now.plusMinutes(5);

        jdbcTemplate.update(
                "insert into processed_message (outbox_id, status, retry_count, next_retry_at, updated_at) values (?, 'RETRY_WAIT', 1, ?, ?)",
                outboxId, future, now
        );

        Map<String, Object> headers = new HashMap<>();
        headers.put("outboxId", outboxId);
        headers.put("eventType", RabbitTopologyConfig.RK_RECORDED);
        headers.put("aggregateType", "auth_error");

        String payload = validRecordedPayload(authErrorId);

        // When: next_retry_at 이전 메시지 전달
        injector.sendWithHeaders(RabbitTopologyConfig.EXCHANGE, RabbitTopologyConfig.RK_RECORDED, payload, headers);

        // Then: 처리 차단(상태 유지)
        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> {
                    String status = jdbcTemplate.queryForObject(
                            "select status from processed_message where outbox_id = ?",
                            String.class,
                            outboxId
                    );
                    assertThat(status).isEqualTo("RETRY_WAIT");
                });

        String authErrorStatusBefore = jdbcTemplate.queryForObject(
                "select status from auth_error where id = ?",
                String.class,
                authErrorId
        );
        assertThat(authErrorStatusBefore).isEqualTo("NEW");

        // When: next_retry_at을 과거로 이동 후 재전달
        OffsetDateTime past = now.minusSeconds(1);
        jdbcTemplate.update(
                "update processed_message set next_retry_at = ?, updated_at = ? where outbox_id = ?",
                past, now, outboxId
        );

        injector.sendWithHeaders(RabbitTopologyConfig.EXCHANGE, RabbitTopologyConfig.RK_RECORDED, payload, headers);

        // Then: 처리 허용(성공 확정)
        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    String status = jdbcTemplate.queryForObject(
                            "select status from processed_message where outbox_id = ?",
                            String.class,
                            outboxId
                    );
                    assertThat(status).isEqualTo("DONE");

                    String authErrorStatus = jdbcTemplate.queryForObject(
                            "select status from auth_error where id = ?",
                            String.class,
                            authErrorId
                    );
                    assertThat(authErrorStatus).isEqualTo("ANALYSIS_REQUESTED");
                });
    }

    private long insertAuthError(String requestId) {
        return Objects.requireNonNull(jdbcTemplate.queryForObject(
                "insert into auth_error (request_id, source_service, environment) values (?, ?, ?) returning id",
                Long.class,
                requestId,
                "test-service",
                "test"
        ));
    }

    private String validRecordedPayload(long authErrorId) {
        return "{"
                + "\"authErrorId\":" + authErrorId + ","
                + "\"requestId\":\"REQ-" + authErrorId + "\","
                + "\"occurredAt\":\"" + OffsetDateTime.now() + "\""
                + "}";
    }

    private void purgeQueue(String queue) {
        if (amqpAdmin != null) {
            amqpAdmin.purgeQueue(queue, true);
        }
    }
}
