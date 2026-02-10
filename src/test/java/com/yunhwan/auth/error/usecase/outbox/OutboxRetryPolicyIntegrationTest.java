package com.yunhwan.auth.error.usecase.outbox;

import com.yunhwan.auth.error.domain.outbox.OutboxMessage;
import com.yunhwan.auth.error.domain.outbox.OutboxStatus;
import com.yunhwan.auth.error.usecase.outbox.config.OutboxProperties;
import com.yunhwan.auth.error.testsupport.stub.StubOutboxPublisher;
import com.yunhwan.auth.error.testsupport.base.AbstractStubIntegrationTest;
import com.yunhwan.auth.error.testsupport.fixtures.OutboxFixtures;
import com.yunhwan.auth.error.usecase.outbox.dto.OutboxClaimResult;
import com.yunhwan.auth.error.usecase.outbox.port.OutboxMessageStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Outbox 재시도 정책 및 DEAD 상태 전환을 검증하는 통합 테스트.
 * <p>
 * 1. 재시도 지연(Backoff): 실패 후 다음 재시도 시간까지는 폴링되지 않아야 함.
 * 2. DEAD 전환: 최대 재시도 횟수(Max Retries)에 도달하면 DEAD 상태로 변경되어야 함.
 */
@DisplayName("[TS-08] Outbox 재시도/DEAD 정책 통합 테스트")
class OutboxRetryPolicyIntegrationTest extends AbstractStubIntegrationTest {

    @Autowired
    OutboxPoller poller;
    @Autowired
    OutboxProcessor processor;
    @Autowired
    OutboxMessageStore outboxMessageStore;
    @Autowired
    StubOutboxPublisher testPublisher;
    @Autowired
    Clock clock;
    @Autowired
    JdbcTemplate jdbcTemplate;
    @Autowired
    OutboxFixtures fixtures;
    @Autowired
    OutboxProperties outboxProperties;

    @Test
    @DisplayName("[TS-08] 재시도 시간 전에는 재폴링 금지, 시간이 지나야 재처리된다")
    void 발행_실패_후_재시도_지연_및_재처리_확인() {
        // Given: 테스트용 메시지 생성
        String scope = newTestScope();
        OutboxMessage m = fixtures.createAuthErrorMessage(scope, "REQ-RETRY" + UUID.randomUUID(), "{\"val\":\"fail-once\"}");

        // 1) 첫 번째 발행은 실패하도록 설정
        testPublisher.failNextRetryable();

        // When: 1차 폴링 및 처리 (실패 발생)
        OutboxClaimResult result = poller.pollOnce(scope);
        List<OutboxMessage> claimed1 = result.claimed();
        assertThat(claimed1).extracting(OutboxMessage::getId).containsExactly(m.getId());

        processor.process(result.owner(), claimed1);

        // Then: 상태는 PENDING, 재시도 횟수 1, 다음 재시도 시간은 미래여야 함
        OutboxMessage afterFail = outboxMessageStore.findById(m.getId()).orElseThrow();
        assertThat(afterFail.getStatus())
                .withFailMessage("실패 후 상태는 PENDING이어야 합니다.")
                .isEqualTo(OutboxStatus.PENDING);
        assertThat(afterFail.getRetryCount())
                .withFailMessage("재시도 횟수가 1이어야 합니다.")
                .isEqualTo(1);
        assertThat(afterFail.getNextRetryAt())
                .withFailMessage("다음 재시도 시간이 설정되어야 합니다.")
                .isNotNull();
        assertThat(afterFail.getNextRetryAt())
                .withFailMessage("다음 재시도 시간은 현재보다 미래여야 합니다.")
                .isAfter(OffsetDateTime.now(clock));

        // When: 즉시 다시 폴링 시도
        OutboxClaimResult result2 = poller.pollOnce(scope);
        List<OutboxMessage> claimed2 = result2.claimed();
        
        // Then: 재시도 시간이 되지 않았으므로 폴링되지 않아야 함
        assertThat(claimed2)
                .withFailMessage("재시도 시간이 되지 않은 메시지는 폴링되지 않아야 합니다.")
                .isEmpty();

        // When: 시간을 강제로 흐르게 함 (next_retry_at을 과거로 변경)
        outboxMessageStore.setNextRetryAt(m.getId(), OffsetDateTime.now(clock).minusSeconds(1), OffsetDateTime.now(clock));

        // 이번엔 발행 성공하도록 설정
        testPublisher.failNext(false);

        // Then: 다시 폴링하면 메시지가 조회되어야 함
        OutboxClaimResult result3 = poller.pollOnce(scope);
        List<OutboxMessage> claimed3 = result3.claimed();
        assertThat(claimed3)
                .withFailMessage("재시도 시간이 지난 메시지는 폴링되어야 합니다.")
                .extracting(OutboxMessage::getId).containsExactly(m.getId());

        processor.process(result3.owner(), claimed3);

        // Then: 최종적으로 PUBLISHED 상태가 되어야 함
        OutboxMessage published = outboxMessageStore.findById(m.getId()).orElseThrow();
        assertThat(published.getStatus())
                .withFailMessage("성공적으로 처리된 메시지는 PUBLISHED 상태여야 합니다.")
                .isEqualTo(OutboxStatus.PUBLISHED);
    }

    @Test
    @DisplayName("[TS-08] 최대 재시도 횟수 도달 시 DEAD로 전환된다")
    void 최대_재시도_횟수_도달_시_DEAD_전환_확인() {
        // Given: 테스트용 메시지 생성
        String scope = newTestScope();
        OutboxMessage m = fixtures.createAuthErrorMessage(scope, "REQ-DEAD" + UUID.randomUUID(), "{\"val\":\"always-fail\"}");

        // 최대 재시도 횟수 직전(max - 1)으로 상태 조작
        int maxRetries = outboxProperties.getRetry().getMaxRetries();
        int currentRetryCount = maxRetries - 1;

        jdbcTemplate.update(
                "update outbox_message set retry_count = ?, updated_at = now() where id = ?",
                currentRetryCount, m.getId()
        );

        // 발행 실패 설정
        testPublisher.failNextRetryable();

        // When: 폴링 및 처리 (마지막 실패 발생)
        OutboxClaimResult result = poller.pollOnce(scope);
        List<OutboxMessage> claimed = result.claimed();
        assertThat(claimed).extracting(OutboxMessage::getId).containsExactly(m.getId());

        processor.process(result.owner(), claimed);

        // Then: DEAD 상태로 전환되었는지 확인
        OutboxMessage reloaded = outboxMessageStore.findById(m.getId()).orElseThrow();
        assertThat(reloaded.getStatus())
                .withFailMessage("최대 재시도 횟수를 초과하면 DEAD 상태여야 합니다.")
                .isEqualTo(OutboxStatus.DEAD);
        
        assertThat(reloaded.getLastError())
                .withFailMessage("에러 메시지가 기록되어야 합니다.")
                .isNotBlank();
        
        // 재시도 횟수는 maxRetries와 같아야 함 (9 -> 10)
        assertThat(reloaded.getRetryCount())
                .withFailMessage("최종 재시도 횟수는 최대 재시도 횟수와 같아야 합니다.")
                .isEqualTo(maxRetries);

        // Processing 관련 필드는 초기화되어야 함
        assertThat(reloaded.getProcessingOwner())
                .withFailMessage("DEAD 상태에서는 Processing Owner가 없어야 합니다.")
                .isNull();
        assertThat(reloaded.getProcessingStartedAt())
                .withFailMessage("DEAD 상태에서는 Processing Started At이 없어야 합니다.")
                .isNull();
    }
}
