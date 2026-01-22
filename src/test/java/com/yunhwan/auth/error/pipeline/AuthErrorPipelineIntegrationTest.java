package com.yunhwan.auth.error.pipeline;

import com.yunhwan.auth.error.domain.autherror.AuthErrorStatus;
import com.yunhwan.auth.error.domain.consumer.ProcessedStatus;
import com.yunhwan.auth.error.domain.outbox.OutboxMessage;
import com.yunhwan.auth.error.domain.outbox.OutboxStatus;
import com.yunhwan.auth.error.testsupport.base.AbstractIntegrationTest;
import com.yunhwan.auth.error.usecase.autherror.AuthErrorWriter;
import com.yunhwan.auth.error.usecase.autherror.port.AuthErrorStore;
import com.yunhwan.auth.error.usecase.consumer.port.ProcessedMessageStore;
import com.yunhwan.auth.error.usecase.outbox.OutboxPoller;
import com.yunhwan.auth.error.usecase.outbox.OutboxProcessor;
import com.yunhwan.auth.error.usecase.outbox.dto.OutboxClaimResult;
import com.yunhwan.auth.error.usecase.outbox.port.OutboxMessageStore;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Auth Error 처리 파이프라인의 정상 흐름(Happy Path)을 검증하는 통합 테스트.
 * <p>
 * 전체 파이프라인 단계:
 * 1. AuthError 기록 (PENDING)
 * 2. [Step 1] Recorded 이벤트 발행 및 처리 -> AuthError 상태: ANALYSIS_REQUESTED
 * 3. [Step 2] Analysis 이벤트 발행 및 처리 -> AuthError 상태: PROCESSED
 * <p>
 * 각 단계마다 OutboxMessage(PUBLISHED), ProcessedMessage(DONE), AuthError 상태 전이를 확인한다.
 */
class AuthErrorPipelineIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private AuthErrorWriter authErrorWriter;
    @Autowired
    private AuthErrorStore authErrorStore;
    @Autowired
    private OutboxMessageStore outboxMessageStore;
    @Autowired
    private ProcessedMessageStore processedMessageStore;
    @Autowired
    private OutboxPoller outboxPoller;
    @Autowired
    private OutboxProcessor outboxProcessor;

    @Test
    @DisplayName("Pipeline Success: AuthError 기록부터 최종 처리(PROCESSED)까지 정상적으로 완료되어야 한다")
    void 파이프라인_정상_처리_및_상태_전이_확인() {
        // Given: AuthError 기록 (초기 상태 PENDING)
        var res = authErrorWriter.record("REQ" + UUID.randomUUID(), OffsetDateTime.now());
        long authErrorId = res.authErrorId();

        // -------------------------------------------------------
        // [Step 1] Recorded 이벤트 처리 (1단계)
        // -------------------------------------------------------
        // When: Poller가 Recorded 이벤트를 가져오고 Processor가 발행
        OutboxClaimResult claim1 = outboxPoller.pollOnce(null);
        var claimed1 = claim1.claimed();
        assertThat(claimed1).hasSize(1);
        long recordedOutboxId = claimed1.getFirst().getId();

        outboxProcessor.process(claim1.owner(), claimed1);

        // Then: 1단계 처리 완료 확인
        Awaitility.await()
                .atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    // 1. AuthError 상태가 ANALYSIS_REQUESTED로 변경되었는지 확인
                    var ae = authErrorStore.findById(authErrorId).orElseThrow();
                    assertThat(ae.getStatus())
                            .withFailMessage("1단계 처리 후 AuthError 상태는 ANALYSIS_REQUESTED여야 합니다.")
                            .isEqualTo(AuthErrorStatus.ANALYSIS_REQUESTED);

                    // 2. OutboxMessage 상태가 PUBLISHED로 변경되었는지 확인
                    OutboxMessage outbox = outboxMessageStore.findById(recordedOutboxId).orElseThrow();
                    assertThat(outbox.getStatus())
                            .withFailMessage("OutboxMessage 상태는 PUBLISHED여야 합니다.")
                            .isEqualTo(OutboxStatus.PUBLISHED);

                    // 3. ProcessedMessage 상태가 DONE으로 변경되었는지 확인
                    var pm = processedMessageStore.findById(recordedOutboxId).orElseThrow();
                    assertThat(pm.getStatus())
                            .withFailMessage("ProcessedMessage 상태는 DONE이어야 합니다.")
                            .isEqualTo(ProcessedStatus.DONE);
                });

        // -------------------------------------------------------
        // [Step 2] Analysis 이벤트 처리 (2단계)
        // -------------------------------------------------------
        // When: 1단계 핸들러에 의해 생성된 Analysis 이벤트를 Polling & Processing
        OutboxClaimResult claim2 = outboxPoller.pollOnce(null);
        var claimed2 = claim2.claimed();
        assertThat(claimed2)
                .withFailMessage("Analysis 단계의 Outbox 메시지가 생성되어 있어야 합니다.")
                .hasSize(1);
        long analysisOutboxId = claimed2.getFirst().getId();

        outboxProcessor.process(claim2.owner(), claimed2);

        // Then: 2단계 처리 완료 및 최종 상태 확인
        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    // 1. AuthError 상태가 최종적으로 PROCESSED로 변경되었는지 확인
                    var ae = authErrorStore.findById(authErrorId).orElseThrow();
                    assertThat(ae.getStatus())
                            .withFailMessage("최종적으로 AuthError 상태는 PROCESSED여야 합니다.")
                            .isEqualTo(AuthErrorStatus.PROCESSED);

                    // 2. Analysis OutboxMessage 상태가 PUBLISHED인지 확인
                    OutboxMessage outbox = outboxMessageStore.findById(analysisOutboxId).orElseThrow();
                    assertThat(outbox.getStatus())
                            .withFailMessage("Analysis OutboxMessage 상태는 PUBLISHED여야 합니다.")
                            .isEqualTo(OutboxStatus.PUBLISHED);

                    // 3. Analysis ProcessedMessage 상태가 DONE인지 확인
                    var pm = processedMessageStore.findById(analysisOutboxId).orElseThrow();
                    assertThat(pm.getStatus())
                            .withFailMessage("Analysis ProcessedMessage 상태는 DONE이어야 합니다.")
                            .isEqualTo(ProcessedStatus.DONE);
                });
    }
}
