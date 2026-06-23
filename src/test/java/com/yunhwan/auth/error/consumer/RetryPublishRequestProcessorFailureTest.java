package com.yunhwan.auth.error.consumer;

import com.yunhwan.auth.error.common.exception.RetryablePublishException;
import com.yunhwan.auth.error.domain.consumer.RetryPublishStatus;
import com.yunhwan.auth.error.infra.messaging.rabbit.RabbitTopologyConfig;
import com.yunhwan.auth.error.testsupport.base.AbstractIntegrationTest;
import com.yunhwan.auth.error.usecase.consumer.RetryPublishRequestPoller;
import com.yunhwan.auth.error.usecase.consumer.RetryPublishRequestProcessor;
import com.yunhwan.auth.error.usecase.consumer.port.RetryPublishRequestPublisher;
import com.yunhwan.auth.error.usecase.consumer.port.RetryPublishRequestStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

@DisplayName("[01] Retry publish request publish failure 테스트")
class RetryPublishRequestProcessorFailureTest extends AbstractIntegrationTest {

    @Autowired
    RetryPublishRequestStore store;
    @Autowired
    RetryPublishRequestPoller poller;
    @Autowired
    RetryPublishRequestProcessor processor;
    @Autowired
    JdbcTemplate jdbcTemplate;
    @Autowired
    Clock clock;

    @MockBean
    RetryPublishRequestPublisher publisher;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("delete from retry_publish_request");
    }

    @Test
    @DisplayName("publish 실패는 retry publish request를 유실하지 않고 PENDING으로 되돌린다")
    void publish_failure_keeps_retry_request_pending() throws Exception {
        OffsetDateTime now = OffsetDateTime.now(clock);
        var request = store.enqueue(
                2002L,
                RabbitTopologyConfig.RK_RECORDED,
                "AUTH_ERROR",
                "{\"authErrorId\":2}",
                1,
                now.plusSeconds(1),
                "retryable",
                now
        );

        doThrow(new RetryablePublishException("confirm timeout", null))
                .when(publisher).publish(any());

        var claim = poller.pollOnce();
        assertThat(claim.claimed()).hasSize(1);

        processor.process(claim.owner(), claim.claimed());

        var reloaded = store.findById(request.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(RetryPublishStatus.PENDING);
        assertThat(reloaded.getPublishRetryCount()).isEqualTo(1);
        assertThat(reloaded.getLastPublishError()).contains("confirm timeout");
        assertThat(reloaded.getPublishedAt()).isNull();
    }
}
