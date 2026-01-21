package com.yunhwan.auth.error.usecase.outbox;

import com.yunhwan.auth.error.domain.outbox.OutboxMessage;
import com.yunhwan.auth.error.domain.outbox.OutboxStatus;
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

@DisplayName("Outbox Reaper 통합 테스트")
class OutboxReaperIntegrationTest extends AbstractStubIntegrationTest {

    @Autowired
    OutboxPoller poller;
    @Autowired
    OutboxReaper reaper;
    @Autowired
    Clock clock;
    @Autowired
    OutboxMessageStore outboxMessageStore;
    @Autowired
    JdbcTemplate jdbcTemplate;
    @Autowired
    OutboxFixtures fixtures;

    /**
     * Stale 메시지 복구 검증:
     * PROCESSING 상태로 너무 오래 머물러 있는(Stale) 메시지는
     * Reaper에 의해 감지되어 PENDING 상태로 되돌려져야 한다.
     * 이때 재시도 횟수가 증가하고, 다음 재시도 시간이 설정되어야 한다.
     */
    @Test
    @DisplayName("오랫동안 처리중인(Stale) 메시지는 PENDING 상태로 복구되고 재시도 정보가 갱신된다")
    void 오랫동안_처리중인_메시지는_PENDING_상태로_복구되고_재시도_정보가_갱신된다() {
        // given: 메시지 생성
        String scope = newTestScope();
        OutboxMessage m = fixtures.createAuthErrorMessage(scope, "REQ-STUCK" + UUID.randomUUID(), "{\"val\":\"x\"}");

        // poller가 claim해서 PROCESSING 상태로 만든다
        OutboxClaimResult result = poller.pollOnce(scope);
        List<OutboxMessage> claimed = result.claimed();
        assertThat(claimed).extracting(OutboxMessage::getId).containsExactly(m.getId());

        OutboxMessage processing = outboxMessageStore.findById(m.getId()).orElseThrow();
        assertThat(processing.getStatus()).isEqualTo(OutboxStatus.PROCESSING);

        // processing_started_at을 "충분히 과거"로 만들어 stale 처리되게 함 (10분 전)
        OffsetDateTime past = OffsetDateTime.now(clock).minusMinutes(10);
        jdbcTemplate.update(
                "update outbox_message set processing_started_at = ?, updated_at = now() where id = ?",
                past, m.getId()
        );

        // when: Reaper 실행
        int reaped = reaper.reapOnce(scope);

        // then
        assertThat(reaped).isEqualTo(1);

        OutboxMessage after = outboxMessageStore.findById(m.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(after.getRetryCount()).isEqualTo(1);
        assertThat(after.getNextRetryAt()).isNotNull();
        assertThat(after.getNextRetryAt()).isAfter(OffsetDateTime.now(clock));
        assertThat(after.getLastError()).contains("STALE_REAP");

        // PROCESSING 필드 정리됐는지 확인
        assertThat(after.getProcessingOwner()).isNull();
        assertThat(after.getProcessingStartedAt()).isNull();
    }
}
