package com.yunhwan.auth.error.consumer;

import com.yunhwan.auth.error.domain.consumer.RetryPublishStatus;
import com.yunhwan.auth.error.infra.messaging.rabbit.RabbitTopologyConfig;
import com.yunhwan.auth.error.testsupport.base.AbstractIntegrationTest;
import com.yunhwan.auth.error.testsupport.config.TestFailInjectionConfig;
import com.yunhwan.auth.error.testsupport.messaging.DuplicateDeliveryInjector;
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
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("[01] Consumer retry publish request 통합 테스트")
class ConsumerRetryPublishRequestIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    DuplicateDeliveryInjector injector;
    @Autowired
    TestFailInjectionConfig.FailInjectedAuthErrorHandler recordedFailInjector;
    @Autowired
    TestFailInjectionConfig.FailInjectedAuthErrorHandler analysisFailInjector;
    @Autowired
    JdbcTemplate jdbcTemplate;
    @Autowired
    AmqpAdmin amqpAdmin;
    @Autowired
    RabbitTemplate rabbitTemplate;

    @BeforeEach
    void setUp() {
        recordedFailInjector.reset();
        analysisFailInjector.reset();
        purgeQueue(RabbitTopologyConfig.Q_RECORDED);
        purgeQueue(RabbitTopologyConfig.RETRY_Q_RECORDED_10S);
        purgeQueue(RabbitTopologyConfig.RETRY_Q_RECORDED_1M);
        purgeQueue(RabbitTopologyConfig.RETRY_Q_RECORDED_10M);
        jdbcTemplate.update("delete from retry_publish_request");
        jdbcTemplate.update("delete from processed_message");
        jdbcTemplate.update("delete from outbox_message");
        jdbcTemplate.update("delete from auth_error_cluster_item");
        jdbcTemplate.update("delete from auth_error_analysis_result");
        jdbcTemplate.update("delete from auth_error_cluster");
        jdbcTemplate.update("delete from auth_error");
    }

    @Test
    @DisplayName("retryable failure는 retry publish request를 저장하고 RabbitMQ retry queue에 직접 발행하지 않는다")
    void retryable_failure_persists_request_without_direct_publish() {
        long authErrorId = insertAuthError("REQ-RETRY-" + UUID.randomUUID());
        long outboxId = Math.abs(UUID.randomUUID().getMostSignificantBits());

        recordedFailInjector.failFirst(1);

        Map<String, Object> headers = new HashMap<>();
        headers.put("outboxId", outboxId);
        headers.put("eventType", RabbitTopologyConfig.RK_RECORDED);
        headers.put("aggregateType", "AUTH_ERROR");

        injector.sendWithHeaders(
                RabbitTopologyConfig.EXCHANGE,
                RabbitTopologyConfig.RK_RECORDED,
                validRecordedPayload(authErrorId),
                headers
        );

        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> {
                    Long count = jdbcTemplate.queryForObject(
                            "select count(*) from retry_publish_request where source_outbox_id = ?",
                            Long.class,
                            outboxId
                    );
                    assertThat(count).isEqualTo(1L);

                    String processedStatus = jdbcTemplate.queryForObject(
                            "select status from processed_message where outbox_id = ?",
                            String.class,
                            outboxId
                    );
                    assertThat(processedStatus).isEqualTo("RETRY_WAIT");
                });

        Message directRetryMessage = rabbitTemplate.receive(RabbitTopologyConfig.RETRY_Q_RECORDED_10S);
        assertThat(directRetryMessage)
                .withFailMessage("Consumer는 retry 메시지를 RabbitMQ retry queue에 직접 발행하면 안 됩니다.")
                .isNull();
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
