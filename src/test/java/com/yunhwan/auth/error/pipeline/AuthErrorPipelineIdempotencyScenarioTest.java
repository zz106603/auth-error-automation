package com.yunhwan.auth.error.pipeline;

import com.yunhwan.auth.error.domain.autherror.AuthErrorStatus;
import com.yunhwan.auth.error.domain.consumer.ProcessedStatus;
import com.yunhwan.auth.error.infra.messaging.rabbit.RabbitTopologyConfig;
import com.yunhwan.auth.error.testsupport.base.AbstractIntegrationTest;
import com.yunhwan.auth.error.testsupport.config.TestFailInjectionConfig;
import com.yunhwan.auth.error.testsupport.messaging.DuplicateDeliveryInjector;
import com.yunhwan.auth.error.usecase.autherror.AuthErrorWriter;
import com.yunhwan.auth.error.usecase.autherror.dto.AuthErrorWriteCommand;
import com.yunhwan.auth.error.usecase.autherror.port.AuthErrorStore;
import com.yunhwan.auth.error.usecase.consumer.port.ProcessedMessageStore;
import com.yunhwan.auth.error.usecase.outbox.OutboxPoller;
import com.yunhwan.auth.error.usecase.outbox.OutboxProcessor;
import com.yunhwan.auth.error.usecase.outbox.port.OutboxMessageStore;
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

@Tag("scenario")
@DisplayName("[TS-09] Consumer idempotency and duplicate delivery protection")
class AuthErrorPipelineIdempotencyScenarioTest extends AbstractIntegrationTest {

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
    JdbcTemplate jdbcTemplate;

    @Autowired
    TestFailInjectionConfig.FailInjectedAuthErrorHandler recordedFailInjector;
    @Autowired
    TestFailInjectionConfig.FailInjectedAuthErrorHandler analysisFailInjector;

    @Autowired
    DuplicateDeliveryInjector duplicateDeliveryInjector;

    @BeforeEach
    void setUp() {
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
    @DisplayName("[TS-09] Idempotency: 이미 처리 완료된(DONE) 메시지를 중복 수신할 경우 재처리하지 않아야 한다")
    void 파이프라인_중복_수신_시_멱등성_보장_확인() {
        // Given: 모든 단계가 정상 처리되도록 설정
        recordedFailInjector.reset();
        analysisFailInjector.reset();

        var recordResult = authErrorWriter.record(newTestCommand());
        long authErrorId = recordResult.authErrorId();

        // -------------------------------------------------------
        // [Step 1] Recorded 이벤트 처리
        // -------------------------------------------------------
        var claim1 = outboxPoller.pollOnce(null);
        long recordedOutboxId = claim1.claimed().getFirst().getId();
        outboxProcessor.process(claim1.owner(), claim1.claimed());

        // 1단계 완료 대기
        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    var ae = authErrorStore.findById(authErrorId).orElseThrow();
                    assertThat(ae.getStatus()).isEqualTo(AuthErrorStatus.ANALYSIS_REQUESTED);
                });

        // -------------------------------------------------------
        // [Step 2] Analysis 이벤트 처리
        // -------------------------------------------------------
        var claim2 = outboxPoller.pollOnce(null);
        long analysisOutboxId = claim2.claimed().getFirst().getId();
        outboxProcessor.process(claim2.owner(), claim2.claimed());

        // 2단계 완료 대기 (DONE)
        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    var pm = processedMessageStore.findById(analysisOutboxId).orElseThrow();
                    assertThat(pm.getStatus()).isEqualTo(ProcessedStatus.DONE);
                });

        // AuthError 최종 완료 확인
        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    var ae = authErrorStore.findById(authErrorId).orElseThrow();
                    assertThat(ae.getStatus()).isEqualTo(AuthErrorStatus.ANALYSIS_COMPLETED);
                });

        // -------------------------------------------------------
        // [Step 3] 중복 메시지 전송 (멱등성 테스트)
        // -------------------------------------------------------
        // 현재 상태 스냅샷 저장 (업데이트 시간, 재시도 횟수)
        var before = processedMessageStore.findById(analysisOutboxId).orElseThrow();
        var beforeUpdatedAt = before.getUpdatedAt();
        var beforeRetryCount = before.getRetryCount();

        // When: 동일한 Analysis Outbox 메시지를 강제로 중복 전송
        var outbox = outboxMessageStore.findById(analysisOutboxId).orElseThrow();
        duplicateDeliveryInjector.sendDuplicate(
                RabbitTopologyConfig.EXCHANGE,
                outbox.getEventType(),
                outbox.getPayload(),
                analysisOutboxId,
                outbox.getEventType(),
                outbox.getAggregateType()
        );

        // Then: 일정 시간이 지나도 상태나 메타데이터가 변하지 않아야 함 (재처리 Skip)
        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    var after = processedMessageStore.findById(analysisOutboxId).orElseThrow();

                    // 상태는 여전히 DONE
                    assertThat(after.getStatus()).isEqualTo(ProcessedStatus.DONE);

                    // 업데이트 시간과 재시도 횟수가 이전과 동일해야 함 (로직 실행 안 됨)
                    assertThat(after.getUpdatedAt())
                            .withFailMessage("중복 메시지는 처리되지 않아야 하므로 업데이트 시간이 같아야 합니다.")
                            .isEqualTo(beforeUpdatedAt);
                    assertThat(after.getRetryCount())
                            .withFailMessage("중복 메시지는 처리되지 않아야 하므로 재시도 횟수가 같아야 합니다.")
                            .isEqualTo(beforeRetryCount);
                    assertThat(countProcessedMessageByOutboxId(analysisOutboxId))
                            .withFailMessage("processed_message는 outbox_id 기준으로 1건만 존재해야 합니다.")
                            .isEqualTo(1L);
                });
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

    private long countProcessedMessageByOutboxId(long outboxId) {
        Long count = jdbcTemplate.queryForObject(
                "select count(*) from processed_message where outbox_id = ?",
                Long.class,
                outboxId
        );
        return count == null ? 0L : count;
    }
}
