package com.yunhwan.auth.error.consumer;

import com.yunhwan.auth.error.domain.consumer.RetryPublishStatus;
import com.yunhwan.auth.error.infra.messaging.rabbit.RabbitTopologyConfig;
import com.yunhwan.auth.error.testsupport.base.AbstractIntegrationTest;
import com.yunhwan.auth.error.usecase.consumer.RetryPublishRequestPoller;
import com.yunhwan.auth.error.usecase.consumer.RetryPublishRequestProcessor;
import com.yunhwan.auth.error.usecase.consumer.port.RetryPublishRequestStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("[01] Retry publish request poller 통합 테스트")
class RetryPublishRequestPollerIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    RetryPublishRequestStore store;
    @Autowired
    RetryPublishRequestPoller poller;
    @Autowired
    RetryPublishRequestProcessor processor;
    @Autowired
    RabbitTemplate rabbitTemplate;
    @Autowired
    AmqpAdmin amqpAdmin;
    @Autowired
    JdbcTemplate jdbcTemplate;
    @Autowired
    Clock clock;

    @BeforeEach
    void setUp() {
        purgeQueue(RabbitTopologyConfig.RETRY_Q_RECORDED_10S);
        jdbcTemplate.update("delete from retry_publish_request");
    }

    @Test
    @DisplayName("poller는 retry publish request를 retry exchange로 발행하고 PUBLISHED로 마감한다")
    void poller_publishes_retry_request_to_retry_exchange() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        var request = store.enqueue(
                1001L,
                RabbitTopologyConfig.RK_RECORDED,
                "AUTH_ERROR",
                "{\"authErrorId\":1}",
                1,
                now.plusSeconds(1),
                "retryable",
                now
        );

        var claim = poller.pollOnce();
        assertThat(claim.claimed()).hasSize(1);

        processor.process(claim.owner(), claim.claimed());

        var reloaded = store.findById(request.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(RetryPublishStatus.PUBLISHED);

        Message published = rabbitTemplate.receive(RabbitTopologyConfig.RETRY_Q_RECORDED_10S);
        assertThat(published).isNotNull();
        assertThat(published.getMessageProperties().getHeaders().get("outboxId")).isEqualTo(1001L);
        assertThat(published.getMessageProperties().getHeaders().get("x-retry-count")).isEqualTo(1);
    }

    private void purgeQueue(String queue) {
        if (amqpAdmin != null) {
            amqpAdmin.purgeQueue(queue, true);
        }
    }
}
