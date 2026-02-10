package com.yunhwan.auth.error.usecase.outbox;

import com.yunhwan.auth.error.domain.outbox.OutboxMessage;
import com.yunhwan.auth.error.domain.outbox.OutboxStatus;
import com.yunhwan.auth.error.testsupport.stub.StubOutboxPublisher;
import com.yunhwan.auth.error.testsupport.base.AbstractStubIntegrationTest;
import com.yunhwan.auth.error.testsupport.fixtures.OutboxFixtures;
import com.yunhwan.auth.error.usecase.outbox.dto.OutboxClaimResult;
import com.yunhwan.auth.error.usecase.outbox.port.OutboxMessageStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Outbox Processor의 메시지 처리 로직을 검증하는 통합 테스트.
 * <p>
 * Poller가 가져온 메시지를 Processor가 처리할 때:
 * 1. 발행 성공 시: 상태가 PUBLISHED로 변경되어야 함.
 * 2. 발행 실패 시: 상태가 PENDING으로 유지되고, 재시도 횟수 증가 및 다음 재시도 시간이 설정되어야 함.
 */
@DisplayName("[TS-08] Outbox publish 실패 처리 통합 테스트")
class OutboxProcessingIntegrationTest extends AbstractStubIntegrationTest {

    @Autowired
    OutboxPoller poller;
    @Autowired
    OutboxProcessor processor;
    @Autowired
    OutboxMessageStore outboxMessageStore;
    @Autowired
    Clock clock;
    @Autowired
    StubOutboxPublisher testPublisher;
    @Autowired
    OutboxFixtures fixtures;

    @Test
    @DisplayName("[TS-08] 발행 성공 시 outbox 상태는 PUBLISHED로 전환된다")
    void 메시지_발행_성공_시_상태를_PUBLISHED로_변경한다() {
        // Given: 테스트용 PENDING 메시지 생성
        String scope = newTestScope();
        OutboxMessage m = fixtures.createAuthErrorMessage(scope, "REQ-1" + UUID.randomUUID(), "{\"val\":\"ok\"}");

        // Publisher가 성공하도록 설정
        testPublisher.failNext(false);

        // When: 메시지 폴링(Claim) 및 처리(Process)
        OutboxClaimResult result = poller.pollOnce(scope);
        List<OutboxMessage> claimed = result.claimed();
        processor.process(result.owner(), claimed);

        // Then: 메시지 상태가 PUBLISHED로 변경되었는지 확인
        Optional<OutboxMessage> reloaded = outboxMessageStore.findById(m.getId());
        assertThat(reloaded).isPresent();
        assertThat(reloaded.get().getStatus())
                .withFailMessage("발행 성공 시 상태는 PUBLISHED여야 합니다.")
                .isEqualTo(OutboxStatus.PUBLISHED);
    }

    @Test
    @DisplayName("[TS-08] retryable 발행 실패는 재시도 전제 PENDING + retry 정보 갱신")
    void retryable_예외_발생_시_재시도를_위해_상태를_PENDING으로_변경하고_재시도_정보를_업데이트한다() {
        // Given: 테스트용 PENDING 메시지 생성
        String scope = newTestScope();
        OutboxMessage m = fixtures.createAuthErrorMessage(scope, "REQ-FAIL" + UUID.randomUUID(), "{\"val\":\"fail\"}");

        // Publisher가 retryable 실패(예외 발생)하도록 설정
        testPublisher.failNextRetryable();

        // When: 메시지 폴링(Claim) 및 처리(Process)
        OutboxClaimResult result = poller.pollOnce(scope);
        List<OutboxMessage> claimed = result.claimed();
        processor.process(result.owner(), claimed);

        // Then: 메시지 상태 및 재시도 정보 확인
        Optional<OutboxMessage> reloaded = outboxMessageStore.findById(m.getId());
        assertThat(reloaded).isPresent();

        OutboxMessage msg = reloaded.get();
        
        // 1. 상태는 여전히 PENDING이어야 함 (재시도 대기)
        assertThat(msg.getStatus())
                .withFailMessage("발행 실패 시 상태는 PENDING이어야 합니다.")
                .isEqualTo(OutboxStatus.PENDING);
        
        // 2. 재시도 횟수가 1 증가해야 함
        assertThat(msg.getRetryCount())
                .withFailMessage("재시도 횟수가 1 증가해야 합니다.")
                .isEqualTo(1);
        
        // 3. 다음 재시도 시간이 현재 시간 이후로 설정되어야 함
        assertThat(msg.getNextRetryAt())
                .withFailMessage("다음 재시도 시간이 설정되어야 합니다.")
                .isAfter(OffsetDateTime.now(clock));
        
        // 4. 에러 메시지가 기록되어야 함
        assertThat(msg.getLastError())
                .withFailMessage("에러 메시지가 기록되어야 합니다.")
                .contains("Test exception");
    }

    @Test
    @DisplayName("[TS-08] non-retryable 발행 실패는 즉시 DEAD로 전환된다")
    void non_retryable_예외_발생_시_즉시_DEAD로_전환한다() {
        // Given: 테스트용 PENDING 메시지 생성
        String scope = newTestScope();
        OutboxMessage m = fixtures.createAuthErrorMessage(scope, "REQ-NON-RETRY" + UUID.randomUUID(), "{\"val\":\"dead\"}");

        // Publisher가 non-retryable 실패하도록 설정
        testPublisher.failNextNonRetryable();

        // When: 메시지 폴링(Claim) 및 처리(Process)
        OutboxClaimResult result = poller.pollOnce(scope);
        List<OutboxMessage> claimed = result.claimed();
        processor.process(result.owner(), claimed);

        // Then: 메시지 상태 및 재시도 정보 확인
        Optional<OutboxMessage> reloaded = outboxMessageStore.findById(m.getId());
        assertThat(reloaded).isPresent();

        OutboxMessage msg = reloaded.get();

        assertThat(msg.getStatus())
                .withFailMessage("non-retryable 실패 시 상태는 DEAD여야 합니다.")
                .isEqualTo(OutboxStatus.DEAD);

        assertThat(msg.getNextRetryAt())
                .withFailMessage("DEAD 상태에서는 next_retry_at이 없어야 합니다.")
                .isNull();

        assertThat(msg.getProcessingOwner())
                .withFailMessage("DEAD 상태에서는 Processing Owner가 없어야 합니다.")
                .isNull();
        assertThat(msg.getProcessingStartedAt())
                .withFailMessage("DEAD 상태에서는 Processing Started At이 없어야 합니다.")
                .isNull();

        assertThat(msg.getLastError())
                .withFailMessage("에러 메시지가 기록되어야 합니다.")
                .contains("Test exception");
    }
}
