package com.yunhwan.auth.error.pipeline;

import com.yunhwan.auth.error.domain.autherror.AuthErrorStatus;
import com.yunhwan.auth.error.domain.consumer.ProcessedStatus;
import com.yunhwan.auth.error.infra.messaging.rabbit.RabbitTopologyConfig;
import com.yunhwan.auth.error.testsupport.base.AbstractIntegrationTest;
import com.yunhwan.auth.error.testsupport.config.TestFailInjectionConfig;
import com.yunhwan.auth.error.testsupport.messaging.DuplicateDeliveryInjector;
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
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Auth Error 처리 파이프라인의 실패 및 예외 시나리오를 검증하는 통합 테스트.
 * <p>
 * 1. 지속적 실패 시 DLQ 이동 및 DEAD 상태 처리
 * 2. 일시적 실패 후 재시도를 통한 성공 처리
 * 3. 중복 메시지 수신 시 멱등성 보장
 */
class AuthErrorPipelineFailureIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    AuthErrorWriter authErrorWriter;
    @Autowired
    OutboxPoller outboxPoller;
    @Autowired
    OutboxProcessor outboxProcessor;

    @Autowired
    OutboxMessageStore outboxMessageStore;
    @Autowired
    ProcessedMessageStore processedMessageStore;
    @Autowired
    AuthErrorStore authErrorStore;

    @Autowired
    TestFailInjectionConfig.FailInjectedAuthErrorHandler testAuthErrorHandler; // 실패 주입용
    @Autowired
    DuplicateDeliveryInjector duplicateDeliveryInjector;
    @Autowired
    StubDlqObserver dlqObserver;                  // DLQ 관측용

    @BeforeEach
    void setUp() {
        dlqObserver.reset();
        processedMessageStore.deleteAll();
    }

    @Test
    @DisplayName("Pipeline FAIL: 핸들러 지속 실패 시 DLQ로 이동하고 상태는 DEAD가 되며, AuthError는 처리되지 않아야 한다")
    void 파이프라인_실패_시_DLQ_이동_및_DEAD_상태_확인() {
        // Given: 핸들러가 무조건 실패하도록 설정
        testAuthErrorHandler.failAlways();

        // AuthError 기록 (초기 상태 PENDING)
        var recordResult = authErrorWriter.record("REQ-IT-1", OffsetDateTime.now());
        long authErrorId = recordResult.authErrorId();

        // When: Outbox Polling 및 Publishing 수행 (Consumer가 메시지를 수신하게 됨)
        var claim = outboxPoller.pollOnce(null);
        assertThat(claim.claimed()).hasSize(1);

        long outboxId = claim.claimed().getFirst().getId();
        outboxProcessor.process(claim.owner(), claim.claimed());

        // Then: DLQ에 메시지가 도착했는지 확인 (재시도 후 최종 실패)
        Awaitility.await()
                .atMost(Duration.ofSeconds(60))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    assertThat(dlqObserver.count())
                            .withFailMessage("DLQ에 메시지가 도착해야 합니다.")
                            .isEqualTo(1L);
                    assertThat(dlqObserver.lastOutboxId())
                            .withFailMessage("DLQ 메시지의 ID가 일치해야 합니다.")
                            .isEqualTo(outboxId);
                });

        // Then: ProcessedMessage 상태가 DEAD로 변경되었는지 확인
        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    var pm = processedMessageStore.findById(outboxId).orElseThrow();
                    assertThat(pm.getStatus())
                            .withFailMessage("ProcessedMessage 상태는 DEAD여야 합니다.")
                            .isEqualTo(ProcessedStatus.DEAD);
                });

        // Then: AuthError 상태는 PROCESSED가 아니어야 함 (실패했으므로)
        var ae = authErrorStore.findById(authErrorId).orElseThrow();
        assertThat(ae.getStatus())
                .withFailMessage("AuthError 상태는 PROCESSED가 아니어야 합니다.")
                .isNotEqualTo(AuthErrorStatus.PROCESSED);
    }

    @Test
    @DisplayName("Pipeline OK after retry: 핸들러 1회 실패 후 재시도하여 성공하면 최종적으로 DONE 및 PROCESSED 상태가 되어야 한다")
    void 파이프라인_재시도_후_성공_확인() {
        // Given: 첫 1회만 실패하도록 설정
        testAuthErrorHandler.failFirst(1);

        var recordResult = authErrorWriter.record("REQ-IT-2", OffsetDateTime.now());
        long authErrorId = recordResult.authErrorId();

        // When: Outbox Polling 및 Publishing 수행
        var claim = outboxPoller.pollOnce(null);
        assertThat(claim.claimed()).hasSize(1);

        long outboxId = claim.claimed().getFirst().getId();
        outboxProcessor.process(claim.owner(), claim.claimed());

        // Then: DLQ에는 메시지가 없어야 함
        Awaitility.await()
                .atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(dlqObserver.count()).isEqualTo(0L));

        // Then: ProcessedMessage 상태가 DONE으로 변경되었는지 확인
        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    var pm = processedMessageStore.findById(outboxId).orElseThrow();
                    assertThat(pm.getStatus())
                            .withFailMessage("ProcessedMessage 상태는 DONE이어야 합니다.")
                            .isEqualTo(ProcessedStatus.DONE);
                });

        // Then: AuthError 상태가 PROCESSED로 변경되었는지 확인
        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    var ae = authErrorStore.findById(authErrorId).orElseThrow();
                    assertThat(ae.getStatus())
                            .withFailMessage("AuthError 상태는 PROCESSED여야 합니다.")
                            .isEqualTo(AuthErrorStatus.PROCESSED);
                });
    }

    @Test
    @DisplayName("Step3: 동일 outboxId 중복 수신 시 이미 DONE 상태라면 재처리되지 않아야 한다")
    void 파이프라인_중복_수신_시_멱등성_보장_확인() {
        // Given: 정상 처리되도록 설정
        testAuthErrorHandler.failFirst(0);

        var recordResult = authErrorWriter.record("REQ-IT-3", OffsetDateTime.now());
        long authErrorId = recordResult.authErrorId();

        var claim = outboxPoller.pollOnce(null);
        assertThat(claim.claimed()).hasSize(1);

        long outboxId = claim.claimed().getFirst().getId();
        outboxProcessor.process(claim.owner(), claim.claimed());

        // 1. 먼저 정상적으로 DONE 상태가 될 때까지 대기
        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    var pm = processedMessageStore.findById(outboxId).orElseThrow();
                    assertThat(pm.getStatus()).isEqualTo(ProcessedStatus.DONE);
                });

        // 2. 현재 상태 스냅샷 저장
        var before = processedMessageStore.findById(outboxId).orElseThrow();
        var beforeUpdatedAt = before.getUpdatedAt();
        var beforeRetryCount = before.getRetryCount();

        // When: 동일한 메시지를 중복 전송
        var outbox = outboxMessageStore.findById(outboxId).orElseThrow();
        duplicateDeliveryInjector.sendDuplicate(
                RabbitTopologyConfig.EXCHANGE,
                outbox.getEventType(),
                outbox.getPayload(),
                outboxId,
                outbox.getEventType(),
                outbox.getAggregateType()
        );

        // Then: 일정 시간 후에도 상태나 업데이트 시간이 변하지 않아야 함 (재처리 Skip)
        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    var after = processedMessageStore.findById(outboxId).orElseThrow();
                    assertThat(after.getStatus())
                            .withFailMessage("상태는 여전히 DONE이어야 합니다.")
                            .isEqualTo(ProcessedStatus.DONE);

                    // 재처리 흔적이 없어야 함 (업데이트 시간 및 재시도 횟수 동일)
                    assertThat(after.getUpdatedAt())
                            .withFailMessage("중복 메시지는 처리되지 않아야 하므로 업데이트 시간이 같아야 합니다.")
                            .isEqualTo(beforeUpdatedAt);
                    assertThat(after.getRetryCount())
                            .withFailMessage("중복 메시지는 처리되지 않아야 하므로 재시도 횟수가 같아야 합니다.")
                            .isEqualTo(beforeRetryCount);
                });

        // Then: AuthError 상태도 여전히 PROCESSED여야 함
        var ae = authErrorStore.findById(authErrorId).orElseThrow();
        assertThat(ae.getStatus()).isEqualTo(AuthErrorStatus.PROCESSED);
    }
}
