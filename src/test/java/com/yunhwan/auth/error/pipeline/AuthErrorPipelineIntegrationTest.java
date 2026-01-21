package com.yunhwan.auth.error.pipeline;

import com.yunhwan.auth.error.domain.autherror.AuthError;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Auth Error 처리 파이프라인 전체를 검증하는 통합 테스트.
 * <p>
 * 1. AuthError 기록 (PENDING)
 * 2. Outbox Polling & Publishing (PUBLISHED)
 * 3. Consumer 처리 (DONE)
 * 4. 최종적으로 AuthError 상태 업데이트 (PROCESSED)
 * <p>
 * 위 전체 흐름이 유기적으로 동작하는지 확인한다.
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
    @DisplayName("파이프라인 통합 테스트: 정상 처리 시 AuthError 상태가 PROCESSED로 변경되어야 한다")
    void 파이프라인_정상_처리_및_상태_전이_확인() {
        // Given: AuthError 기록 (초기 상태 PENDING)
        var res = authErrorWriter.record("REQ-IT-1", OffsetDateTime.now());

        // When: Outbox Polling 및 Publishing 수행
        OutboxClaimResult result = outboxPoller.pollOnce(null);
        var claimed = result.claimed();
        assertThat(claimed).hasSize(1);
        long outboxId = claimed.getFirst().getId();

        outboxProcessor.process(result.owner(), claimed);

        // Then: Consumer 처리가 완료될 때까지 기다리며 전체 상태 검증
        Awaitility.await()
            .atMost(Duration.ofSeconds(15))
            .pollInterval(Duration.ofMillis(200))
            .untilAsserted(() -> {
                // 1. AuthError 상태가 PROCESSED로 변경되었는지 확인
                AuthError reloaded = authErrorStore.findById(res.authErrorId()).orElseThrow();
                assertThat(reloaded.getStatus())
                        .withFailMessage("AuthError 상태는 PROCESSED여야 합니다.")
                        .isEqualTo(AuthErrorStatus.PROCESSED);

                // 2. OutboxMessage 상태가 PUBLISHED로 변경되었는지 확인
                OutboxMessage outbox = outboxMessageStore.findById(outboxId).orElseThrow();
                assertThat(outbox.getStatus())
                        .withFailMessage("OutboxMessage 상태는 PUBLISHED여야 합니다.")
                        .isEqualTo(OutboxStatus.PUBLISHED);

                // 3. ProcessedMessage 상태가 DONE으로 변경되었는지 확인
                var pm = processedMessageStore.findById(outboxId).orElseThrow();
                assertThat(pm.getStatus())
                        .withFailMessage("ProcessedMessage 상태는 DONE이어야 합니다.")
                        .isEqualTo(ProcessedStatus.DONE);
            });
    }
}
