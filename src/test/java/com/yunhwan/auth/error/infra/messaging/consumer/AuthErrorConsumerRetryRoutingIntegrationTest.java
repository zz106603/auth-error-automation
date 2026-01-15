package com.yunhwan.auth.error.infra.messaging.consumer;

import com.yunhwan.auth.error.infra.messaging.rabbit.RabbitTopologyConfig;
import com.yunhwan.auth.error.testsupport.stub.StubAuthErrorHandler;
import com.yunhwan.auth.error.testsupport.base.AbstractStubIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AuthErrorConsumer의 재시도 라우팅 로직을 검증하는 통합 테스트.
 * <p>
 * 소비 실패 시 재시도 횟수(x-retry-count)가 증가하며,
 * 그 횟수에 따라 적절한 대기 큐(10s, 1m, 10m)로 메시지가 라우팅되는지 확인한다.
 */
@ActiveProfiles("stub")
@SpringBootTest(properties = {
        "spring.rabbitmq.listener.simple.auto-startup=true",
        "spring.rabbitmq.listener.direct.auto-startup=true",
        "outbox.retry.max-retries=10",
        "outbox.retry.delay-seconds=10"
})
class AuthErrorConsumerRetryRoutingIntegrationTest extends AbstractStubIntegrationTest {

    private static final long TEST_OUTBOX_ID = 1000L;

    @Autowired
    RabbitTemplate rabbitTemplate;
    @Autowired
    RabbitAdmin rabbitAdmin;
    @Autowired
    StubAuthErrorHandler testAuthErrorHandler;

    @BeforeEach
    void setUp() {
        // 핸들러 상태 초기화
        testAuthErrorHandler.reset();

        // 테스트 간섭 방지를 위해 Retry 큐 초기화
        rabbitAdmin.purgeQueue(RabbitTopologyConfig.RETRY_Q_10S, true);
        rabbitAdmin.purgeQueue(RabbitTopologyConfig.RETRY_Q_1M, true);
        rabbitAdmin.purgeQueue(RabbitTopologyConfig.RETRY_Q_10M, true);

        // DLQ 초기화
        rabbitAdmin.purgeQueue(RabbitTopologyConfig.DLQ, true);
    }

    @Test
    void 다음_재시도_횟수가_1이면_10초_대기큐로_라우팅된다() {
        // Given: 이번 소비에서 1번 실패하도록 설정 (재시도 발생)
        testAuthErrorHandler.failFirst(1);

        // When: 현재 재시도 횟수 0 -> 다음 재시도 횟수 1
        sendMain(TEST_OUTBOX_ID, 0);

        // Then: 10초 대기 큐에 메시지가 존재해야 함
        Message m10s = rabbitTemplate.receive(RabbitTopologyConfig.RETRY_Q_10S, 3000);
        assertThat(m10s).isNotNull();

        // 다른 대기 큐에는 메시지가 없어야 함
        assertThat(rabbitTemplate.receive(RabbitTopologyConfig.RETRY_Q_1M, 200)).isNull();
        assertThat(rabbitTemplate.receive(RabbitTopologyConfig.RETRY_Q_10M, 200)).isNull();

        // 헤더 검증 (x-retry-count 증가 확인)
        assertThat(m10s.getMessageProperties().getHeaders().get("x-retry-count")).isEqualTo(1);
    }

    @Test
    void 다음_재시도_횟수가_4이면_1분_대기큐로_라우팅된다() {
        testAuthErrorHandler.failFirst(1);

        // When: 현재 재시도 횟수 3 -> 다음 재시도 횟수 4 (Ladder: 4~6 => 1분)
        sendMain(TEST_OUTBOX_ID, 3);

        Message m1m = rabbitTemplate.receive(RabbitTopologyConfig.RETRY_Q_1M, 3000);
        assertThat(m1m).isNotNull();

        assertThat(rabbitTemplate.receive(RabbitTopologyConfig.RETRY_Q_10S, 200)).isNull();
        assertThat(rabbitTemplate.receive(RabbitTopologyConfig.RETRY_Q_10M, 200)).isNull();

        assertThat(m1m.getMessageProperties().getHeaders().get("x-retry-count")).isEqualTo(4);
    }

    @Test
    void 다음_재시도_횟수가_7이면_10분_대기큐로_라우팅된다() {
        testAuthErrorHandler.failFirst(1);

        // When: 현재 재시도 횟수 6 -> 다음 재시도 횟수 7 (Ladder: 7+ => 10분)
        sendMain(TEST_OUTBOX_ID, 6);

        Message m10m = rabbitTemplate.receive(RabbitTopologyConfig.RETRY_Q_10M, 3000);
        assertThat(m10m).isNotNull();

        assertThat(rabbitTemplate.receive(RabbitTopologyConfig.RETRY_Q_10S, 200)).isNull();
        assertThat(rabbitTemplate.receive(RabbitTopologyConfig.RETRY_Q_1M, 200)).isNull();

        assertThat(m10m.getMessageProperties().getHeaders().get("x-retry-count")).isEqualTo(7);
    }

    private void sendMain(long outboxId, int currentRetry) {
        rabbitTemplate.convertAndSend(
                RabbitTopologyConfig.EXCHANGE,
                RabbitTopologyConfig.ROUTING_KEY,
                "{\"msg\":\"hello\"}",
                msg -> {
                    msg.getMessageProperties().setHeader("outboxId", outboxId);
                    msg.getMessageProperties().setHeader("x-retry-count", currentRetry);

                    // Consumer가 Headers를 구성할 때 사용하는 값들
                    msg.getMessageProperties().setHeader("eventType", "V1");
                    msg.getMessageProperties().setHeader("aggregateType", "AUTH_ERROR");
                    return msg;
                }
        );
    }
}
