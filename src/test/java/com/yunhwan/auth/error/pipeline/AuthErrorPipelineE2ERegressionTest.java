package com.yunhwan.auth.error.pipeline;

import com.yunhwan.auth.error.domain.autherror.AuthErrorStatus;
import com.yunhwan.auth.error.domain.consumer.ProcessedStatus;
import com.yunhwan.auth.error.testsupport.base.AbstractIntegrationTest;
import com.yunhwan.auth.error.testsupport.config.TestFailInjectionConfig;
import com.yunhwan.auth.error.testsupport.stub.StubDlqObserver;
import com.yunhwan.auth.error.usecase.autherror.AuthErrorWriter;
import com.yunhwan.auth.error.usecase.autherror.dto.AuthErrorWriteCommand;
import com.yunhwan.auth.error.usecase.autherror.port.AuthErrorStore;
import com.yunhwan.auth.error.usecase.consumer.port.ProcessedMessageStore;
import com.yunhwan.auth.error.usecase.outbox.OutboxPoller;
import com.yunhwan.auth.error.usecase.outbox.OutboxProcessor;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("e2e")
@Tag("non-scenario")
@DisplayName("[E2E] AuthError pipeline failure/retry regression")
class AuthErrorPipelineE2ERegressionTest extends AbstractIntegrationTest {

    @Autowired
    AuthErrorWriter authErrorWriter;
    @Autowired
    OutboxPoller outboxPoller;
    @Autowired
    OutboxProcessor outboxProcessor;

    @Autowired
    ProcessedMessageStore processedMessageStore;
    @Autowired
    AuthErrorStore authErrorStore;
    @Autowired
    JdbcTemplate jdbcTemplate;

    // 테스트 시나리오를 위해 각 단계(Recorded, Analysis)별로 실패를 주입할 수 있는 빈
    @Autowired
    TestFailInjectionConfig.FailInjectedAuthErrorHandler recordedFailInjector;
    @Autowired
    TestFailInjectionConfig.FailInjectedAuthErrorHandler analysisFailInjector;

    @Autowired
    StubDlqObserver dlqObserver;

    @BeforeEach
    void setUp() {
        dlqObserver.reset();
        processedMessageStore.deleteAll();
        jdbcTemplate.update("delete from processed_message");
        jdbcTemplate.update("delete from outbox_message");
        jdbcTemplate.update("delete from auth_error_cluster_item");
        jdbcTemplate.update("delete from auth_error_analysis_result");
        jdbcTemplate.update("delete from auth_error_cluster");
        jdbcTemplate.update("delete from auth_error");

        // 테스트 간 간섭 방지를 위해 실패 주입기 초기화
        recordedFailInjector.reset();
        analysisFailInjector.reset();
    }

    @Test
    @DisplayName("Pipeline FAIL: Analysis 단계에서 지속적으로 실패하면 DLQ로 이동하고 상태는 DEAD가 되며, AuthError는 완료되지 않아야 한다")
    void 파이프라인_실패_시_DLQ_이동_및_DEAD_상태_확인() {
        // Given: Analysis 단계(2단계)만 무조건 실패하도록 설정
        recordedFailInjector.reset();
        analysisFailInjector.failAlways();

        // 초기 데이터 기록 (AuthError 생성)
        var recordResult = authErrorWriter.record(newTestCommand());
        long authErrorId = recordResult.authErrorId();

        // -------------------------------------------------------
        // [Step 1] Recorded 이벤트 처리 (1단계)
        // -------------------------------------------------------
        // When: Poller가 메시지를 가져오고 Processor가 발행
        var claim1 = outboxPoller.pollOnce(null);
        assertThat(claim1.claimed()).hasSize(1);

        long recordedOutboxId = claim1.claimed().getFirst().getId();
        outboxProcessor.process(claim1.owner(), claim1.claimed());

        // Then: 1단계 처리가 성공하면 AuthError 상태는 ANALYSIS_REQUESTED로 변경되어야 함
        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    var ae = authErrorStore.findById(authErrorId).orElseThrow();
                    assertThat(ae.getStatus()).isEqualTo(AuthErrorStatus.ANALYSIS_REQUESTED);
                });

        // -------------------------------------------------------
        // [Step 2] Analysis 이벤트 처리 (2단계 - 여기서 실패 발생)
        // -------------------------------------------------------
        // When: 1단계 핸들러에 의해 생성된 Analysis Outbox 메시지를 Polling & Processing
        var claim2 = outboxPoller.pollOnce(null);
        assertThat(claim2.claimed())
                .withFailMessage("Analysis 단계의 Outbox 메시지가 생성되어 있어야 합니다.")
                .hasSize(1);

        long analysisOutboxId = claim2.claimed().getFirst().getId();
        outboxProcessor.process(claim2.owner(), claim2.claimed());

        // Then: 재시도 횟수를 모두 소진하고 DLQ로 이동했는지 확인
        Awaitility.await()
                .atMost(Duration.ofSeconds(60)) // 재시도 대기 시간 고려
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    assertThat(dlqObserver.count())
                            .withFailMessage("DLQ에 메시지가 도착해야 합니다.")
                            .isEqualTo(1L);
                    assertThat(dlqObserver.lastOutboxId())
                            .withFailMessage("DLQ에 도착한 메시지 ID가 일치해야 합니다.")
                            .isEqualTo(analysisOutboxId);
                });

        // Then: 해당 메시지의 처리 상태는 DEAD여야 함
        Awaitility.await()
                .atMost(Duration.ofSeconds(20))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    var pm = processedMessageStore.findById(analysisOutboxId).orElseThrow();
                    assertThat(pm.getStatus())
                            .withFailMessage("실패한 메시지의 상태는 DEAD여야 합니다.")
                            .isEqualTo(ProcessedStatus.DEAD);
                });

        // Then: AuthError 상태는 최종 완료(PROCESSED)가 아니어야 함
        var ae = authErrorStore.findById(authErrorId).orElseThrow();
        assertThat(ae.getStatus())
                .withFailMessage("AuthError 상태는 PROCESSED가 아니어야 합니다.")
                .isNotEqualTo(AuthErrorStatus.PROCESSED);
    }

    @Test
    @DisplayName("Pipeline OK after retry: Analysis 단계에서 일시적 실패 후 재시도하여 성공하면 최종적으로 완료 상태가 되어야 한다")
    void 파이프라인_재시도_후_성공_확인() {
        // Given: Analysis 단계에서 첫 1회만 실패하고 이후 성공하도록 설정
        recordedFailInjector.reset();
        analysisFailInjector.failFirst(1);

        var recordResult = authErrorWriter.record(newTestCommand());
        long authErrorId = recordResult.authErrorId();

        // -------------------------------------------------------
        // [Step 1] Recorded 이벤트 처리 (1단계)
        // -------------------------------------------------------
        var claim1 = outboxPoller.pollOnce(null);
        assertThat(claim1.claimed()).hasSize(1);

        long recordedOutboxId = claim1.claimed().getFirst().getId();
        outboxProcessor.process(claim1.owner(), claim1.claimed());

        // Then: 1단계 완료 확인 (ANALYSIS_REQUESTED)
        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    var ae = authErrorStore.findById(authErrorId).orElseThrow();
                    assertThat(ae.getStatus())
                            .withFailMessage("1단계 처리 후 상태는 ANALYSIS_REQUESTED여야 합니다.")
                            .isEqualTo(AuthErrorStatus.ANALYSIS_REQUESTED);
                });

        // -------------------------------------------------------
        // [Step 2] Analysis 이벤트 처리 (2단계 - 재시도 발생)
        // -------------------------------------------------------
        var claim2 = outboxPoller.pollOnce(null);
        assertThat(claim2.claimed()).hasSize(1);

        long analysisOutboxId = claim2.claimed().getFirst().getId();
        outboxProcessor.process(claim2.owner(), claim2.claimed());

        // Then: 재시도 후 성공하여 AuthError 상태가 PROCESSED로 변경되었는지 확인
        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    var ae = authErrorStore.findById(authErrorId).orElseThrow();
                    assertThat(ae.getStatus())
                            .withFailMessage("최종적으로 AuthError 상태는 ANALYSIS_COMPLETED여야 합니다.")
                            .isEqualTo(AuthErrorStatus.ANALYSIS_COMPLETED);
                });

        // Then: 두 단계의 Outbox 메시지 모두 DONE 상태인지 확인
        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    assertThat(processedMessageStore.findById(recordedOutboxId).orElseThrow().getStatus())
                            .isEqualTo(ProcessedStatus.DONE);
                    assertThat(processedMessageStore.findById(analysisOutboxId).orElseThrow().getStatus())
                            .isEqualTo(ProcessedStatus.DONE);
                });

        // Then: 성공했으므로 DLQ에는 메시지가 없어야 함
        assertThat(dlqObserver.count()).isEqualTo(0L);
    }

    private AuthErrorWriteCommand newTestCommand() {
        return new AuthErrorWriteCommand(
                "REQ-" + UUID.randomUUID(),
                OffsetDateTime.now(),

                401,                    // httpStatus
                "GET",                  // httpMethod
                "/api/test",            // requestUri
                "127.0.0.1",             // clientIp
                "JUnit",                // userAgent
                "test-user",            // userId
                "test-session",         // sessionId

                "IllegalStateException",
                "test exception",
                null,
                null,
                "stacktrace"
        );
    }
}
