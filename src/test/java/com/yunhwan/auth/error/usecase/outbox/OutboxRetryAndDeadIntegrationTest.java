package com.yunhwan.auth.error.usecase.outbox;

import com.yunhwan.auth.error.domain.outbox.OutboxMessage;
import com.yunhwan.auth.error.domain.outbox.OutboxStatus;
import com.yunhwan.auth.error.usecase.outbox.config.OutboxProperties;
import com.yunhwan.auth.error.testsupport.stub.StubOutboxPublisher;
import com.yunhwan.auth.error.testsupport.base.AbstractStubIntegrationTest;
import com.yunhwan.auth.error.testsupport.fixtures.OutboxFixtures;
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

@DisplayName("Outbox 재시도/DEAD 경계 통합 테스트")
class OutboxRetryAndDeadIntegrationTest extends AbstractStubIntegrationTest {

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

    /**
     * 재시도 지연 검증:
     * 발행 실패 후 재시도 시간이 미래로 설정되면,
     * 즉시 다시 폴링하더라도 해당 메시지는 조회되지 않아야 한다.
     * (시간이 지나거나 강제로 시간을 앞당긴 후에야 다시 조회되어야 함)
     */
    @Test
    @DisplayName("발행 실패 후 재시도 시간이 미래인 경우 즉시 재폴링되지 않고 시간이 지나야 재처리된다")
    void 발행_실패_후_재시도_시간이_미래인_경우_즉시_재폴링되지_않고_시간이_지나야_재처리된다() {
        // given
        String scope = "T-" + UUID.randomUUID() + "-";
        OutboxMessage m = fixtures.createAuthErrorMessage(scope, "REQ-RETRY" + UUID.randomUUID(), "{\"val\":\"fail-once\"}");

        // 1) 첫 publish는 실패하게 설정
        testPublisher.failNext(true);

        // when: 1차 poll -> 처리(실패) -> PENDING + next_retry_at(미래)
        List<OutboxMessage> claimed1 = poller.pollOnce(scope);
        assertThat(claimed1).extracting(OutboxMessage::getId).containsExactly(m.getId());

        processor.process(claimed1);

        // then: DB에 next_retry_at 미래로 설정되어 있어야 함
        OutboxMessage afterFail = outboxMessageStore.findById(m.getId()).orElseThrow();
        assertThat(afterFail.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(afterFail.getRetryCount()).isEqualTo(1);
        assertThat(afterFail.getNextRetryAt()).isNotNull();
        assertThat(afterFail.getNextRetryAt()).isAfter(OffsetDateTime.now(clock));

        // when: 즉시 다시 poll -> next_retry_at 때문에 claim 0건이어야 함
        List<OutboxMessage> claimed2 = poller.pollOnce(scope);
        assertThat(claimed2).isEmpty();

        // when: 시간을 "지났다고 가정"하기 위해 next_retry_at을 과거로 강제
        outboxMessageStore.setNextRetryAt(m.getId(), OffsetDateTime.now(clock).minusSeconds(1), OffsetDateTime.now(clock));

        // 그리고 이번엔 publish 성공하게 설정
        testPublisher.failNext(false);

        // then: 다시 poll하면 claim 된다
        List<OutboxMessage> claimed3 = poller.pollOnce(scope);
        assertThat(claimed3).extracting(OutboxMessage::getId).containsExactly(m.getId());

        processor.process(claimed3);

        OutboxMessage published = outboxMessageStore.findById(m.getId()).orElseThrow();
        assertThat(published.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
    }

    /**
     * DEAD 상태 전환 검증 (경계값 테스트):
     * 최대 재시도 횟수(maxRetries=10)에 도달하면
     * 더 이상 재시도하지 않고 DEAD 상태로 전환되어야 한다.
     * (retry_count가 9인 상태에서 실패 -> 10이 되면서 DEAD)
     */
    @Test
    @DisplayName("최대 재시도 횟수에 도달하면 DEAD 상태로 전환된다")
    void 최대_재시도_횟수에_도달하면_DEAD_상태로_전환된다() {
        // given
        String scope = "T-" + UUID.randomUUID() + "-";
        OutboxMessage m = fixtures.createAuthErrorMessage(scope, "REQ-DEAD" + UUID.randomUUID(), "{\"val\":\"always-fail\"}");

        int maxRetries = outboxProperties.getRetry().getMaxRetries();
        int currentRetryCount = maxRetries - 1;

        // OutboxProcessor 내부 maxRetries=10 이므로, retry_count를 9로 세팅
        // 다음 실패 시 nextRetryCount=10이 되어 DEAD 조건(>=10) 충족
        jdbcTemplate.update(
                "update outbox_message set retry_count = ?, updated_at = now() where id = ?",
                currentRetryCount, m.getId()
        );

        // publish 실패 설정
        testPublisher.failNext(true);

        // when
        List<OutboxMessage> claimed = poller.pollOnce(scope);
        assertThat(claimed).extracting(OutboxMessage::getId).containsExactly(m.getId());

        processor.process(claimed);

        // then
        OutboxMessage reloaded = outboxMessageStore.findById(m.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(OutboxStatus.DEAD);
        assertThat(reloaded.getLastError()).isNotBlank();
        
        // 9에서 한 번 더 실패했으므로 최종 카운트는 10이어야 함
        assertThat(reloaded.getRetryCount()).isEqualTo(maxRetries);

        // PROCESSING 관련 필드는 정리되어야 함
        assertThat(reloaded.getProcessingOwner()).isNull();
        assertThat(reloaded.getProcessingStartedAt()).isNull();
    }
}
