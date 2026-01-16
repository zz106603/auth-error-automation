package com.yunhwan.auth.error.usecase.outbox;

import com.yunhwan.auth.error.domain.outbox.OutboxMessage;
import com.yunhwan.auth.error.domain.outbox.OutboxStatus;
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

@DisplayName("Outbox Reaper -> Poller -> Processor E2E 통합 테스트")
class OutboxReaperEndToEndIntegrationTest extends AbstractStubIntegrationTest {

    @Autowired
    OutboxPoller poller;
    @Autowired
    OutboxReaper reaper;
    @Autowired
    OutboxProcessor processor;
    @Autowired
    OutboxMessageStore outboxMessageStore;
    @Autowired
    Clock clock;
    @Autowired
    StubOutboxPublisher testPublisher;
    @Autowired
    JdbcTemplate jdbcTemplate;
    @Autowired
    OutboxFixtures fixtures;

    /**
     * E2E 복구 시나리오:
     * 1. 메시지가 PROCESSING 상태에서 멈춤(Stuck) -> 장애 상황 가정
     * 2. Reaper가 이를 감지하여 PENDING으로 복구
     * 3. Poller가 다시 가져가서(Claim) 처리(Process)
     * 4. 최종적으로 PUBLISHED 상태로 완료되는지 검증
     */
    @Test
    @DisplayName("처리가 중단된 메시지를 Reaper가 복구하고 다시 처리하여 완료(PUBLISHED)시킨다")
    void 처리가_중단된_메시지를_Reaper가_복구하고_다시_처리하여_완료시킨다() {
        // given: 메시지 생성
        String scope = "T-" + UUID.randomUUID() + "-";
        OutboxMessage m = fixtures.createAuthErrorMessage(scope, "REQ-E2E" + UUID.randomUUID(), "{\"val\":\"e2e\"}");

        // 1) poller가 claim해서 PROCESSING으로 만든다
        List<OutboxMessage> claimed1 = poller.pollOnce(scope);
        assertThat(claimed1).extracting(OutboxMessage::getId).containsExactly(m.getId());

        OutboxMessage processing = outboxMessageStore.findById(m.getId()).orElseThrow();
        assertThat(processing.getStatus()).isEqualTo(OutboxStatus.PROCESSING);

        // 2) processing_started_at을 과거로 밀어서 "stuck" 상태를 만든다 (예: 10분 전)
        OffsetDateTime past = OffsetDateTime.now(clock).minusMinutes(10);
        jdbcTemplate.update(
                "update outbox_message set processing_started_at = ?, updated_at = now() where id = ?",
                past, m.getId()
        );

        // 3) reaper가 stale PROCESSING을 PENDING으로 복구
        int reaped = reaper.reapOnce(scope);
        assertThat(reaped).isEqualTo(1);

        OutboxMessage afterReap = outboxMessageStore.findById(m.getId()).orElseThrow();
        assertThat(afterReap.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(afterReap.getRetryCount()).isEqualTo(1);
        assertThat(afterReap.getNextRetryAt()).isNotNull();
        assertThat(afterReap.getLastError()).contains("STALE_PROCESSING");

        // 4) "재시도 시간이 지났다"를 만들기 위해 next_retry_at을 과거로 강제
        outboxMessageStore.setNextRetryAt(m.getId(), OffsetDateTime.now(clock).minusSeconds(1), OffsetDateTime.now(clock));

        // 5) 이번에는 publish 성공하게 설정
        testPublisher.failNext(false);

        // when: 다시 poll 해서 claim -> processor 처리
        List<OutboxMessage> claimed2 = poller.pollOnce(scope);
        assertThat(claimed2).extracting(OutboxMessage::getId).containsExactly(m.getId());

        processor.process(claimed2);

        // then: 최종 PUBLISHED + 필드 정리
        OutboxMessage published = outboxMessageStore.findById(m.getId()).orElseThrow();
        assertThat(published.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);

        // markPublished()가 정리해주는 필드들 확인
        assertThat(published.getProcessingOwner()).isNull();
        assertThat(published.getProcessingStartedAt()).isNull();
        assertThat(published.getNextRetryAt()).isNull();
        assertThat(published.getLastError()).isNull();

        // retry_count는 정책에 따라 그대로 유지(=1). 성공했다고 0으로 되돌리지 않는 게 일반적.
        assertThat(published.getRetryCount()).isEqualTo(1);
    }
}
