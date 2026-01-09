package com.yunhwan.auth.error.autherror;

import com.yunhwan.auth.error.autherror.repository.AuthErrorRepository;
import com.yunhwan.auth.error.autherror.service.AuthErrorWriter;
import com.yunhwan.auth.error.domain.auth.AuthError;
import com.yunhwan.auth.error.domain.auth.AuthErrorStatus;
import com.yunhwan.auth.error.domain.outbox.OutboxMessage;
import com.yunhwan.auth.error.outbox.service.OutboxPoller;
import com.yunhwan.auth.error.outbox.service.OutboxProcessor;
import com.yunhwan.auth.error.support.AbstractIntegrationTest;
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
    private AuthErrorRepository authErrorRepository;

    @Autowired
    private OutboxPoller outboxPoller;

    @Autowired
    private OutboxProcessor outboxProcessor;

    @Test
    @DisplayName("파이프라인 통합 테스트: 정상 처리 시 AuthError 상태가 PROCESSED로 변경되어야 한다")
    void authError_pipeline_success_test() {
        // given: AuthError 생성 및 DB 적재
        AuthError authError = createAuthError();
        var result = authErrorWriter.record(authError);

        // when: Outbox 메시지 폴링 및 처리 (이벤트 발행 + Outbox 상태 업데이트)
        List<OutboxMessage> claimed = outboxPoller.pollOnce();
        outboxProcessor.process(claimed);

        // then: 컨슈머가 메시지를 소비하고, 최종적으로 AuthError 상태가 PROCESSED로 변경되었는지 검증
        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    AuthError reloaded = authErrorRepository.findById(result.authErrorId()).orElseThrow();
                    assertThat(reloaded.getStatus()).isEqualTo(AuthErrorStatus.PROCESSED);
                });
    }

    private AuthError createAuthError() {
        return AuthError.builder()
                .sourceService("auth-api")
                .environment("test")
                .errorDomain("AUTH")
                .severity("ERROR")
                .status(AuthErrorStatus.NEW)
                .occurredAt(OffsetDateTime.now())
                .receivedAt(OffsetDateTime.now())
                .requestId("REQ-IT-1")
                .dedupKey("DEDUP-IT-1")
                .build();
    }
}
