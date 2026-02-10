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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("[TS-11] Consumer 계약 위반 DLQ 통합 테스트")
class ConsumerContractViolationDlqIntegrationTest extends AbstractStubIntegrationTest {

    @Autowired
    DuplicateDeliveryInjector injector;
    @Autowired
    StubDlqObserver dlqObserver;
    @Autowired
    JdbcTemplate jdbcTemplate;
    @Autowired
    AmqpAdmin amqpAdmin;

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
    @DisplayName("[TS-11] 필수 헤더 누락은 즉시 DLQ + 무부작용")
    void 필수_헤더_누락_시_즉시_DLQ_및_무부작용() {
        // Given
        long beforeProcessed = count("processed_message");
        long beforeAuthError = count("auth_error");
        long beforeOutbox = count("outbox_message");

        long outboxId = Math.abs(UUID.randomUUID().getMostSignificantBits());
        Map<String, Object> headers = new HashMap<>();
        headers.put("outboxId", outboxId);
        headers.put("aggregateType", "auth_error");
        // eventType intentionally missing

        // When
        injector.sendWithHeaders(
                RabbitTopologyConfig.EXCHANGE,
                RabbitTopologyConfig.RK_RECORDED,
                "{\"val\":\"missing-header\"}",
                headers
        );

        // Then: DLQ 적재
        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> {
                    assertThat(dlqObserver.count()).isEqualTo(1L);
                    assertThat(dlqObserver.lastOutboxId()).isEqualTo(outboxId);
                });

        // Then: 무부작용
        assertThat(count("processed_message")).isEqualTo(beforeProcessed);
        assertThat(count("auth_error")).isEqualTo(beforeAuthError);
        assertThat(count("outbox_message")).isEqualTo(beforeOutbox);
    }

    private long count(String table) {
        return jdbcTemplate.queryForObject("select count(*) from " + table, Long.class);
    }

    private void purgeQueue(String queue) {
        if (amqpAdmin != null) {
            amqpAdmin.purgeQueue(queue, true);
        }
    }
}
