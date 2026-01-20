package com.yunhwan.auth.error.infra.messaging.consumer;

import com.yunhwan.auth.error.domain.consumer.ProcessedMessage;
import com.yunhwan.auth.error.domain.consumer.ProcessedStatus;
import com.yunhwan.auth.error.infra.messaging.rabbit.RabbitTopologyConfig;
import com.yunhwan.auth.error.testsupport.base.AbstractStubIntegrationTest;
import com.yunhwan.auth.error.testsupport.stub.StubAuthErrorHandler;
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
 * Consumer의 재시도(Retry) 메커니즘을 검증하는 통합 테스트.
 * <p>
 * 일시적인 오류로 인해 처리에 실패했을 때,
 * 설정된 정책에 따라 재시도가 이루어지고 결국 성공적으로 처리(DONE)되는지 확인한다.
 */
@SpringBootTest(properties = {
        "spring.rabbitmq.listener.simple.auto-startup=true"
})
@DisplayName("Consumer 재시도 메커니즘 통합 테스트")
class AuthErrorConsumerRetryIntegrationTest extends AbstractStubIntegrationTest {

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
    @DisplayName("일시적 실패 후 재시도를 통해 최종적으로 처리가 완료되어야 한다")
    void 재시도_후_최종_성공_및_처리완료_확인() {
        // Given: 테스트용 Outbox ID 생성 및 첫 1회 실패하도록 설정
        long outboxId = UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
        testAuthErrorHandler.failFirst(1); // 첫 번째 호출은 실패하고, 두 번째부터 성공

        // When: 메시지 발행
        rabbitTemplate.send(
                RabbitTopologyConfig.EXCHANGE,
                RabbitTopologyConfig.ROUTING_KEY,
                createMessage(outboxId)
        );

        // Then: 재시도를 거쳐 최종적으로 DONE 상태가 되는지 확인
        await()
            .atMost(Duration.ofSeconds(30))
            .pollInterval(Duration.ofMillis(250))
            .untilAsserted(() -> {
                // 1. DB에서 메시지 상태 확인
                ProcessedMessage pm = processedMessageStore.findById(outboxId).orElseThrow();
                assertEquals(ProcessedStatus.DONE, pm.getStatus(), "최종 상태는 DONE이어야 합니다.");
                assertNotNull(pm.getProcessedAt(), "처리 완료 시간이 기록되어야 합니다.");
                assertNull(pm.getLeaseUntil(), "처리가 완료되면 Lease는 해제되어야 합니다.");

                // 2. 재시도가 실제로 일어났는지 확인 (최소 2회 호출: 1회 실패 + 1회 성공)
                assertTrue(testAuthErrorHandler.getCallCount() >= 2, "재시도를 포함해 핸들러가 2회 이상 호출되어야 합니다.");
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
