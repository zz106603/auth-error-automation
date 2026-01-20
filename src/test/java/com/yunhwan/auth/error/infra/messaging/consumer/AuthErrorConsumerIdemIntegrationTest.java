package com.yunhwan.auth.error.infra.messaging.consumer;

import com.yunhwan.auth.error.domain.consumer.ProcessedMessage;
import com.yunhwan.auth.error.domain.consumer.ProcessedStatus;
import com.yunhwan.auth.error.infra.messaging.rabbit.RabbitTopologyConfig;
import com.yunhwan.auth.error.testsupport.stub.StubAuthErrorHandler;
import com.yunhwan.auth.error.testsupport.base.AbstractStubIntegrationTest;
import com.yunhwan.auth.error.usecase.consumer.port.ProcessedMessageStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * RabbitMQ Consumer의 멱등성(Idempotency)을 검증하는 통합 테스트.
 * <p>
 * 네트워크 지연이나 브로커의 재전송 등으로 인해 동일한 메시지가 여러 번 수신되더라도,
 * 비즈니스 로직은 단 한 번만 실행되어야 함을 보장하는지 확인한다.
 */
@SpringBootTest(properties = {
        "spring.rabbitmq.listener.simple.auto-startup=true",
        "spring.rabbitmq.listener.direct.auto-startup=true"
})
@DisplayName("RabbitMQ Consumer 멱등성 통합 테스트")
class AuthErrorConsumerIdemIntegrationTest extends AbstractStubIntegrationTest {

    @Autowired
    RabbitTemplate rabbitTemplate;
    @Autowired
    StubAuthErrorHandler testAuthErrorHandler;
    @Autowired
    ProcessedMessageStore processedMessageStore;

    @BeforeEach
    void setUp() {
        testAuthErrorHandler.reset();
        processedMessageStore.deleteAll();
    }

    @Test
    @DisplayName("동일한 ID의 메시지를 중복 수신해도 핸들러는 1회만 실행되고 처리가 완료되어야 한다")
    void 동일_메시지_중복_수신시_멱등성_보장_및_처리완료_확인() {
        // Given: 동일한 outboxId를 가진 두 개의 메시지 생성
        long outboxId = UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
        Message m1 = createMessage(outboxId);
        Message m2 = createMessage(outboxId);

        // When: 두 메시지를 연속으로 발행 (중복 수신 상황 시뮬레이션)
        rabbitTemplate.send(RabbitTopologyConfig.EXCHANGE, RabbitTopologyConfig.ROUTING_KEY, m1);
        rabbitTemplate.send(RabbitTopologyConfig.EXCHANGE, RabbitTopologyConfig.ROUTING_KEY, m2);

        // Then: 비동기 처리 결과를 기다리며 검증
        await()
            .atMost(Duration.ofSeconds(10))
            .pollInterval(Duration.ofMillis(100))
            .untilAsserted(() -> {
                // 1. 핸들러(비즈니스 로직)는 정확히 1회만 호출되어야 함
                assertEquals(1, testAuthErrorHandler.getCallCount(), "핸들러는 중복 호출되지 않아야 합니다.");

                // 2. 처리 상태(ProcessedMessage)는 최종적으로 DONE 상태여야 함
                ProcessedMessage pm = processedMessageStore.findById(outboxId).orElseThrow();
                assertEquals(ProcessedStatus.DONE, pm.getStatus(), "메시지 처리 상태는 DONE이어야 합니다.");

                // 3. 처리가 완료되었으므로 점유(Lease)는 해제되어야 함
                assertNull(pm.getLeaseUntil(), "처리가 완료되면 Lease는 null이어야 합니다.");
            });
    }

    private Message createMessage(long outboxId) {
        return MessageBuilder.withBody("{\"msg\":\"hello\"}".getBytes())
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setHeader("outboxId", outboxId)
                .setHeader("eventType", "AUTH_ERROR")
                .setHeader("aggregateType", "AUTH_ERROR")
                .build();
    }
}
