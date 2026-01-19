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
import com.yunhwan.auth.error.usecase.outbox.port.OutboxMessageStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.shaded.org.awaitility.Awaitility;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

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
    void authError_pipeline_success_test() {
        // given
        AuthError authError = createAuthError();
        var result = authErrorWriter.record(authError);

        // when: outbox claim + publish
        List<OutboxMessage> claimed = outboxPoller.pollOnce(null);
        assertThat(claimed).hasSize(1);

        long outboxId = claimed.getFirst().getId();

        outboxProcessor.process(claimed);

        // then: consumer 최종 처리까지 기다리며 3개 상태를 같이 검증
        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    // 1) AuthError
                    AuthError reloaded = authErrorStore.findById(result.authErrorId()).orElseThrow();
                    assertThat(reloaded.getStatus()).isEqualTo(AuthErrorStatus.PROCESSED);

                    // 2) OutboxMessage
                    OutboxMessage outbox = outboxMessageStore.findById(outboxId).orElseThrow();
                    assertThat(outbox.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);

                    // 3) ProcessedMessage
                    var pm = processedMessageStore.findById(outboxId).orElseThrow();
                    assertThat(pm.getStatus()).isEqualTo(ProcessedStatus.DONE);
                });
    }

    private AuthError createAuthError() {
        return AuthError.builder()
                .requestId("REQ-IT-1")
                .occurredAt(OffsetDateTime.now())
                .build();
    }
}
