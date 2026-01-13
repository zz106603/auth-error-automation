package com.yunhwan.auth.error.consumer;

import com.yunhwan.auth.error.config.rabbitmq.RabbitTopologyConfig;
import com.yunhwan.auth.error.consumer.persistence.ProcessedMessageRepository;
import com.yunhwan.auth.error.domain.consumer.ProcessedMessage;
import com.yunhwan.auth.error.domain.consumer.ProcessedStatus;
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
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "spring.rabbitmq.listener.simple.auto-startup=true",
        "spring.rabbitmq.listener.direct.auto-startup=true"
})
@DisplayName("Consumer 재시도 메커니즘 통합 테스트")
class AuthErrorConsumerRetryIntegrationTest extends AbstractStubIntegrationTest {

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private TestAuthErrorHandler testAuthErrorHandler;

    @Autowired
    private ProcessedMessageRepository processedMessageRepo;

    @BeforeEach
    void setUp() {
        testAuthErrorHandler.reset();
        processedMessageRepo.deleteAll();
    }

    @Test
    @DisplayName("일시적 실패 발생 시 재시도 로직이 동작하여 최종적으로 처리가 완료되어야 한다")
    void 일시적_실패_발생_시_재시도_로직이_동작한다_그리고_처리가_완료되어야_한다() {
        // given
        long outboxId = System.nanoTime();
        // 첫 1회 실패 설정: 재시도 메커니즘이 동작하는지 확인하기 위함
        testAuthErrorHandler.failFirst(1);

        Message message = createMessage(outboxId);

        // when
        rabbitTemplate.send(RabbitTopologyConfig.EXCHANGE, RabbitTopologyConfig.ROUTING_KEY, message);

        // then
        // 1. 최종 처리 상태가 DONE인지 확인 (재시도 끝에 성공했음을 검증)
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            ProcessedMessage processedMessage = processedMessageRepo.findById(outboxId).orElseThrow();

            assertAll("처리 결과 검증",
                    () -> assertEquals(ProcessedStatus.DONE, processedMessage.getStatus(), "상태는 DONE이어야 합니다."),
                    () -> assertNotNull(processedMessage.getProcessedAt(), "처리 시간은 존재해야 합니다."),
                    () -> assertNull(processedMessage.getLeaseUntil(), "Lease 점유는 해제되어야 합니다.")
            );
        });

        // 2. 실제로 재시도 헤더(x-retry-count)가 포함된 요청이 처리되었는지 확인
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertTrue(testAuthErrorHandler.getMaxRetrySeen() >= 1,
                        "재시도 헤더(x-retry-count)가 1 이상인 호출이 감지되어야 합니다.")
        );
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
