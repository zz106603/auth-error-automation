package com.yunhwan.auth.error.pipeline;

import com.yunhwan.auth.error.domain.autherror.AuthError;
import com.yunhwan.auth.error.domain.autherror.AuthErrorStatus;
import com.yunhwan.auth.error.domain.consumer.ProcessedMessage;
import com.yunhwan.auth.error.domain.consumer.ProcessedStatus;
import com.yunhwan.auth.error.domain.outbox.OutboxMessage;
import com.yunhwan.auth.error.domain.outbox.OutboxStatus;
import com.yunhwan.auth.error.testsupport.base.AbstractStubIntegrationTest;
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
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/*
    FIXME: Consumer 설계 개선 필요
 */
public class AuthErrorPipelineFailureIntegrationTest extends AbstractStubIntegrationTest {

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
    StubAuthErrorHandler testAuthErrorHandler;
    @Autowired
    StubDlqObserver testDlqObserver;

    @BeforeEach
    void setUp() {
        testAuthErrorHandler.reset();
        processedMessageStore.deleteAll();
    }

    @Test
    @DisplayName("Pipeline: 일시 실패 후 재시도로 성공하면 AuthError=PROCESSED, Outbox=PUBLISHED, Processed=DONE")
    void pipeline_retry_then_success() {
        // given: 첫 1회 실패
        testAuthErrorHandler.failFirst(1);

        AuthError authError = createAuthError("REQ-IT-1");
        var recordResult = authErrorWriter.record(authError);

        // when: outbox publish 트리거
        var claimed = outboxPoller.pollOnce(null);
        assertThat(claimed).hasSize(1);
        long outboxId = claimed.getFirst().getId();

        outboxProcessor.process(claimed);

        // then: consumer 재시도 끝에 DONE/PROCESSED로 수렴
        Awaitility.await()
                .atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    assertThat(testAuthErrorHandler.getCallCount()).isGreaterThanOrEqualTo(2);
                    assertThat(testAuthErrorHandler.getMaxRetrySeen()).isGreaterThanOrEqualTo(1);

                    // auth_error
                    AuthError reloaded = authErrorStore.findById(recordResult.authErrorId()).orElseThrow();
                    assertThat(reloaded.getStatus())
                            .isEqualTo(AuthErrorStatus.PROCESSED);

                    // outbox_message
                    OutboxMessage outbox = outboxMessageStore.findById(outboxId).orElseThrow();
                    assertThat(outbox.getStatus())
                            .isEqualTo(OutboxStatus.PUBLISHED);

                    // processed_message
                    ProcessedMessage pm = processedMessageStore.findById(outboxId).orElseThrow();
                    assertThat(pm.getStatus())
                            .isEqualTo(ProcessedStatus.DONE);
                });

        // 재시도 헤더를 실제로 봤는지(재시도 경로 탔는지) 보강 검증
        assertThat(testAuthErrorHandler.getMaxRetrySeen())
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("Pipeline: 지속 실패 시 maxRetries 초과 후 DLQ로 이동")
    void pipeline_fail_then_dlq() {
        // given: 사실상 무한 실패
        testAuthErrorHandler.failFirst(1_000_000);
        testDlqObserver.reset();

        AuthError authError = createAuthError("REQ-IT-2");
        var recordResult = authErrorWriter.record(authError);

        // when: outbox publish 트리거
        var claimed = outboxPoller.pollOnce(null);
        assertThat(claimed).hasSize(1);
        long outboxId = claimed.getFirst().getId();

        outboxProcessor.process(claimed);

        // then: DLQ 수신 확인 (delay-seconds=1로 줄여놨으니 오래 안 걸림)
        Awaitility.await()
                .atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> {
                    assertThat(testDlqObserver.count()).isEqualTo(1L);
                    assertThat(testDlqObserver.lastOutboxId()).isEqualTo(outboxId);
                });

        // publish 자체는 성공했으니 outbox는 PUBLISHED 고정(소비 실패와 별개)
        OutboxMessage outbox = outboxMessageStore.findById(outboxId).orElseThrow();
        assertThat(outbox.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);

        // auth_error는 "성공(PROCESSED)"는 아니어야 한다 정도만 안전하게 고정
        AuthError reloaded = authErrorStore.findById(recordResult.authErrorId()).orElseThrow();
        assertThat(reloaded.getStatus()).isNotEqualTo(AuthErrorStatus.PROCESSED);

        // (옵션) DLQ로 마감될 때 processed_message도 DONE으로 남기는 정책이면 아래 추가
        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> {
                    ProcessedMessage pm = processedMessageStore.findById(outboxId).orElseThrow();
                    assertThat(pm.getStatus()).isEqualTo(ProcessedStatus.DONE);
                });

        // 핸들러 관측값(정책 확인)
        assertThat(testAuthErrorHandler.getCallCount())
                .isGreaterThanOrEqualTo(3); // maxRetries=3이면 최소 3회는 호출돼야 자연스러움
        assertThat(testAuthErrorHandler.getMaxRetrySeen())
                .isGreaterThanOrEqualTo(2); // 0->1->2 까지 관측되는 게 보통
    }

    private AuthError createAuthError(String reqId) {
        return AuthError.builder()
                .requestId(reqId)
                .occurredAt(OffsetDateTime.now())
                .build();
    }

}
