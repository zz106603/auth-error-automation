package com.yunhwan.auth.error.pipeline;

import com.yunhwan.auth.error.domain.autherror.AuthError;
import com.yunhwan.auth.error.domain.autherror.AuthErrorStatus;
import com.yunhwan.auth.error.domain.consumer.ProcessedMessage;
import com.yunhwan.auth.error.domain.consumer.ProcessedStatus;
import com.yunhwan.auth.error.domain.outbox.OutboxMessage;
import com.yunhwan.auth.error.domain.outbox.OutboxStatus;
import com.yunhwan.auth.error.infra.messaging.rabbit.RabbitTopologyConfig;
import com.yunhwan.auth.error.testsupport.base.AbstractIntegrationTest;
import com.yunhwan.auth.error.testsupport.base.AbstractStubIntegrationTest;
import com.yunhwan.auth.error.testsupport.config.TestFailInjectionConfig;
import com.yunhwan.auth.error.testsupport.stub.StubAuthErrorHandler;
import com.yunhwan.auth.error.testsupport.stub.StubDlqObserver;
import com.yunhwan.auth.error.usecase.autherror.AuthErrorWriter;
import com.yunhwan.auth.error.usecase.autherror.port.AuthErrorStore;
import com.yunhwan.auth.error.usecase.consumer.port.ProcessedMessageStore;
import com.yunhwan.auth.error.usecase.outbox.OutboxPoller;
import com.yunhwan.auth.error.usecase.outbox.OutboxProcessor;
import com.yunhwan.auth.error.usecase.outbox.port.OutboxMessageStore;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/*
    TTL 기반 retry 설계 기준:
    - "재시도 횟수/헤더 관측"은 비동기/타이밍 종속이라 통합테스트에서 flaky
    - "최종 수렴 상태" + "DLQ 도착" + "DB terminal 상태"로 검증
    FIXME: publish, process 설계 문제로 추후 수정 예정
 */
class AuthErrorPipelineFailureIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    AuthErrorWriter authErrorWriter;
    @Autowired
    OutboxPoller outboxPoller;
    @Autowired
    OutboxProcessor outboxProcessor;

    @Autowired
    AuthErrorStore authErrorStore;
    @Autowired
    OutboxMessageStore outboxMessageStore;
    @Autowired
    ProcessedMessageStore processedMessageStore;

    @Autowired
    TestFailInjectionConfig.FailInjectedAuthErrorHandler handler;

    @Autowired
    StubDlqObserver testDlqObserver;

    @BeforeEach
    void setUp() {
        testDlqObserver.reset();
        processedMessageStore.deleteAll();
    }

    @Test
    @DisplayName("Pipeline: 일시 실패 후 TTL 재시도로 성공하면 AuthError=PROCESSED, Outbox=PUBLISHED, Processed=DONE")
    void pipeline_retry_then_success() {
        // given: 첫 1회 실패
        handler.failFirst(1);

        var recordResult = authErrorWriter.record("REQ-IT-1", OffsetDateTime.now());

        // when: outbox publish 트리거
        var claimed = outboxPoller.pollOnce(null);
        assertThat(claimed).hasSize(1);
        long outboxId = claimed.getFirst().getId();

        outboxProcessor.process(claimed);

        // 1) outbox publish 자체는 성공했는지
        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> {
                    OutboxMessage outbox = outboxMessageStore.findById(outboxId).orElseThrow();
                    assertThat(outbox.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
                });

        // 2) consumer가 outboxId를 "한 번이라도" 만졌는지 (ProcessedMessage row 생성)
        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> {
                    assertThat(processedMessageStore.findById(outboxId)).isPresent();
                });


        // then: 최종 상태 수렴만 검증 (TTL 대기 고려해서 여유)
        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    // auth_error
                    AuthError reloaded = authErrorStore.findById(recordResult.authErrorId()).orElseThrow();
                    assertThat(reloaded.getStatus()).isEqualTo(AuthErrorStatus.PROCESSED);

                    // outbox_message (publish 성공은 소비 성공/실패와 별개)
                    OutboxMessage outbox = outboxMessageStore.findById(outboxId).orElseThrow();
                    assertThat(outbox.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);

                    // processed_message
                    ProcessedMessage pm = processedMessageStore.findById(outboxId).orElseThrow();
                    assertThat(pm.getStatus()).isEqualTo(ProcessedStatus.DONE);
                });
    }

    @Test
    @DisplayName("Pipeline: 지속 실패 시 maxRetries 초과 후 DLQ로 이동")
    void pipeline_fail_then_dlq() {
        // given: 사실상 무한 실패
        handler.failFirst(1_000_000);

        var recordResult = authErrorWriter.record("REQ-IT-2", OffsetDateTime.now());

        // when: outbox publish 트리거
        var claimed = outboxPoller.pollOnce(null);
        assertThat(claimed).hasSize(1);
        long outboxId = claimed.getFirst().getId();

        outboxProcessor.process(claimed);

        // then: DLQ 수신 확인 (TTL/재시도 큐 다 돌고 최종 DLQ까지 고려해서 여유)
        Awaitility.await()
                .atMost(Duration.ofSeconds(60))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    assertThat(testDlqObserver.count()).isEqualTo(1L);
                    assertThat(testDlqObserver.lastOutboxId()).isEqualTo(outboxId);
                });

        // publish 자체는 성공했으니 outbox는 PUBLISHED 고정
        OutboxMessage outbox = outboxMessageStore.findById(outboxId).orElseThrow();
        assertThat(outbox.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);

        // auth_error는 성공(PROCESSED)이 아니어야 함 (정확한 실패 상태는 정책에 따라 다름)
        AuthError reloaded = authErrorStore.findById(recordResult.authErrorId()).orElseThrow();
        assertThat(reloaded.getStatus()).isNotEqualTo(AuthErrorStatus.PROCESSED);

        // processed_message terminal 상태 검증 (정책에 맞춰 1개만 선택)
        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    ProcessedMessage pm = processedMessageStore.findById(outboxId).orElseThrow();

                    // ✅ 권장: DLQ면 DEAD 같은 상태로 남기는 정책
                     assertThat(pm.getStatus()).isEqualTo(ProcessedStatus.DEAD);

                    // ✅ 만약 너의 현재 구현이 DLQ도 "DONE으로 마감" 정책이면 이걸 유지
//                    assertThat(pm.getStatus()).isEqualTo(ProcessedStatus.DONE);
                });
    }
}
