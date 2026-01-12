package com.yunhwan.auth.error.consumer;

import com.yunhwan.auth.error.config.rabbitmq.RabbitTopologyConfig;
import com.yunhwan.auth.error.consumer.persistence.ProcessedMessageRepository;
import com.yunhwan.auth.error.stub.TestAuthErrorHandler;
import com.yunhwan.auth.error.support.AbstractStubIntegrationTest;
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

import static org.awaitility.Awaitility.await;
import static org.junit.Assert.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * RabbitMQ Consumer의 멱등성(Idempotency)을 검증하는 통합 테스트입니다.
 * <p>
 * 실제 운영 환경과 유사하게 RabbitMQ를 띄우고 메시지를 발행하여,
 * 중복된 메시지가 도착했을 때 핸들러가 정확히 한 번만 실행되는지 확인합니다.
 */
@SpringBootTest(properties = {
        "spring.rabbitmq.listener.simple.auto-startup=true",
        "spring.rabbitmq.listener.direct.auto-startup=true"
})
@DisplayName("RabbitMQ Consumer 멱등성 통합 테스트")
class AuthErrorIdemIntegrationTest extends AbstractStubIntegrationTest {

    @Autowired
    RabbitTemplate rabbitTemplate;

    /**
     * Mockito의 @SpyBean 대신 직접 구현한 Test Double을 사용하여 검증합니다.
     * 장점:
     * 1. Spring Context 오염 방지 (Context Caching 활용 가능)
     * 2. Mockito 프록시 오버헤드 제거
     * 3. 멀티스레드 환경에서 AtomicInteger를 통한 정확한 호출 횟수 추적
     */
    @Autowired
    TestAuthErrorHandler testAuthErrorHandler;

    @Autowired
    ProcessedMessageRepository processedMessageRepo;

    @BeforeEach
    void setUp() {
        testAuthErrorHandler.reset();
        processedMessageRepo.deleteAll();
    }

    @Test
    @DisplayName("동일한 outboxId를 가진 메시지가 2번 전송되면 핸들러는 1번만 실행되어야 한다")
    void 동일한_OutboxID_메시지_중복수신시_핸들러는_한번만_실행된다() {
        // given
        long outboxId = System.nanoTime();
        Message m1 = createMessage(outboxId);
        Message m2 = createMessage(outboxId); // 동일한 ID를 가진 중복 메시지

        // when
        rabbitTemplate.send(RabbitTopologyConfig.EXCHANGE, RabbitTopologyConfig.ROUTING_KEY, m1);
        rabbitTemplate.send(RabbitTopologyConfig.EXCHANGE, RabbitTopologyConfig.ROUTING_KEY, m2);

        // then
        // 비동기 Consumer 처리를 기다립니다.
        // Awaitility를 사용하여 최대 2초간 대기하며, 조건이 만족되면 즉시 테스트를 통과시킵니다.
        await().atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertEquals(1, testAuthErrorHandler.getCallCount()));

        // 최종적으로 핸들러가 정확히 1번만 호출되었는지 재확인 (혹시 모를 추가 호출 방지)
        assertEquals(1, testAuthErrorHandler.getCallCount());

        // 멱등성: processed_message도 1건만
        assertTrue(processedMessageRepo.existsById(outboxId));
        assertEquals(1L, processedMessageRepo.count());
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
