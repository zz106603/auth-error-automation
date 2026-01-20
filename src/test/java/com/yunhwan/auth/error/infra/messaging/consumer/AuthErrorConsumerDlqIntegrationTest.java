package com.yunhwan.auth.error.infra.messaging.consumer;

import com.yunhwan.auth.error.domain.consumer.ProcessedStatus;
import com.yunhwan.auth.error.infra.messaging.rabbit.RabbitTopologyConfig;
import com.yunhwan.auth.error.testsupport.base.AbstractStubIntegrationTest;
import com.yunhwan.auth.error.testsupport.stub.StubAuthErrorHandler;
import com.yunhwan.auth.error.testsupport.stub.StubDlqObserver;
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
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Consumer의 DLQ(Dead Letter Queue) 처리 로직을 검증하는 통합 테스트.
 * <p>
 * 최대 재시도 횟수를 초과하여 처리에 실패한 메시지가
 * DLQ로 올바르게 이동하고, 최종적으로 DEAD 상태로 기록되는지 확인한다.
 */
@SpringBootTest(properties = {
        "spring.rabbitmq.listener.simple.auto-startup=true",
        "spring.rabbitmq.listener.direct.auto-startup=true",

        // 이 테스트는 "첫 실패에 바로 DEAD"를 검증하기 위해 최대 재시도 횟수를 1로 설정
        "outbox.retry.max-retries=1"
})
@DisplayName("Consumer DLQ 처리 통합 테스트")
class AuthErrorConsumerDlqIntegrationTest extends AbstractStubIntegrationTest {

    @Autowired
    RabbitTemplate rabbitTemplate;
    @Autowired
    StubAuthErrorHandler testAuthErrorHandler;
    @Autowired
    StubDlqObserver testDlqObserver;
    @Autowired
    ProcessedMessageStore processedMessageStore;

    @BeforeEach
    void setUp() {
        testAuthErrorHandler.reset();
        testDlqObserver.reset();
        processedMessageStore.deleteAll();
    }

    @Test
    @DisplayName("최대 재시도 횟수 초과 시 메시지는 DLQ로 이동하고 DEAD 상태가 되어야 한다")
    void 재시도_초과시_DLQ_이동_및_DEAD_상태_확인() {
        // Given: 테스트용 Outbox ID 생성 및 핸들러가 무조건 실패하도록 설정
        long outboxId = UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
        testAuthErrorHandler.failFirst(1_000_000); // 충분히 큰 횟수만큼 실패하게 설정

        // When: 메시지 발행
        rabbitTemplate.send(
                RabbitTopologyConfig.EXCHANGE,
                RabbitTopologyConfig.ROUTING_KEY,
                createMessage(outboxId)
        );

        // Then: DLQ로 이동하고 DB 상태가 DEAD로 변경되었는지 확인
        await()
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> {
                    // 1. DLQ Consumer가 메시지를 수신했는지 확인
                    assertEquals(1L, testDlqObserver.count(), "DLQ Consumer가 메시지를 수신해야 합니다.");
                    assertEquals(outboxId, testDlqObserver.lastOutboxId(), "수신된 메시지의 ID가 일치해야 합니다.");

                    // 2. DB에서 해당 메시지의 상태가 DEAD로 변경되었는지 확인
                    assertEquals(ProcessedStatus.DEAD,
                            processedMessageStore.findStatusByOutboxId(outboxId).orElse(null),
                            "메시지 상태는 DEAD여야 합니다."
                    );
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
