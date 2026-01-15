package com.yunhwan.auth.error.infra.messaging.consumer;

import com.yunhwan.auth.error.infra.messaging.rabbit.RabbitTopologyConfig;
import com.yunhwan.auth.error.testsupport.stub.StubAuthErrorHandler;
import com.yunhwan.auth.error.testsupport.stub.StubDlqObserver;
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

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "spring.rabbitmq.listener.simple.auto-startup=true",
        "spring.rabbitmq.listener.direct.auto-startup=true",
        "outbox.retry.max-retries=3",
        "outbox.retry.delay-seconds=10"
})
@DisplayName("Consumer DLQ(Dead Letter Queue) 처리 통합 테스트")
class AuthErrorConsumerDlqIntegrationTest extends AbstractStubIntegrationTest {

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private StubAuthErrorHandler testAuthErrorHandler;

    @Autowired
    private ProcessedMessageStore processedMessageStore;

    @Autowired
    private StubDlqObserver testDlqObserver;

    @BeforeEach
    void setUp() {
        testAuthErrorHandler.reset();
        testDlqObserver.reset();
        processedMessageStore.deleteAll();
    }

    @Test
    @DisplayName("지속적인 실패 발생 시 최대 재시도 횟수 초과 후 DLQ로 메시지가 이동해야 한다")
    void 지속적인_실패_발생시_최대_재시도_후_DLQ로_이동해야_한다() {
        // given
        long outboxId = System.nanoTime();
        // 사실상 무한 실패 설정 (DLQ 이동 유도)
        testAuthErrorHandler.failFirst(1_000_000);

        Message message = createMessage(outboxId);

        // when
        rabbitTemplate.send(RabbitTopologyConfig.EXCHANGE, RabbitTopologyConfig.ROUTING_KEY, message);

        // then
        // 재시도(약 10초 간격) * 3회 + DLQ 처리 시간을 고려하여 대기 시간 설정
        await().atMost(Duration.ofSeconds(45)).untilAsserted(() -> {
            assertAll("DLQ 처리 결과 검증",
                    () -> assertEquals(1L, testDlqObserver.count(), "DLQ Consumer가 메시지를 1회 수신해야 합니다."),
                    () -> assertEquals(outboxId, testDlqObserver.lastOutboxId(), "DLQ로 전달된 메시지의 ID가 일치해야 합니다.")
            );
        });

        assertTrue(testAuthErrorHandler.getCallCount() >= 3,
                "maxRetries=3이면 (첫 시도 포함) 총 3회 이상 handle 되어야 합니다.");

        assertTrue(testAuthErrorHandler.getMaxRetrySeen() >= 2,
                "maxRetries=3이면 마지막 재시도 소비에서 retry header는 2까지 관측됩니다.");
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
