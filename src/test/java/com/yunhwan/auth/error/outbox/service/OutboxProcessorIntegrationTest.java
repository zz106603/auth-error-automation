package com.yunhwan.auth.error.outbox.service;

import com.yunhwan.auth.error.domain.outbox.OutboxMessage;
import com.yunhwan.auth.error.domain.outbox.OutboxStatus;
import com.yunhwan.auth.error.outbox.persistence.OutboxMessageRepository;
import com.yunhwan.auth.error.outbox.publisher.TestOutboxPublisher;
import com.yunhwan.auth.error.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Outbox Processor 통합 테스트")
class OutboxProcessorIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    OutboxPoller poller;
    @Autowired
    OutboxProcessor processor;
    @Autowired
    OutboxMessageRepository repo;
    @Autowired
    TestOutboxPublisher testPublisher;

    @BeforeEach
    void setUp() {
        repo.deleteAll();
    }

    /**
     * 발행 성공 시나리오:
     * 메시지 발행이 성공하면(TestPublisher가 성공 리턴),
     * 해당 메시지의 상태는 PUBLISHED로 변경되어야 한다.
     */
    @Test
    @DisplayName("메시지 발행 성공 시 상태를 PUBLISHED로 변경한다")
    void 메시지_발행_성공_시_상태를_PUBLISHED로_변경한다() {
        // given: PENDING 메시지 생성
        OutboxMessage m = createMessage("REQ-1", "{\"val\":\"ok\"}");

        // 발행 성공 설정
        testPublisher.failNext(false);

        // when: 폴링 후 프로세싱
        List<OutboxMessage> claimed = poller.pollOnce();
        processor.process(claimed);

        // then
        Optional<OutboxMessage> reloaded = repo.findById(m.getId());
        assertThat(reloaded).isPresent();
        assertThat(reloaded.get().getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
    }

    /**
     * 발행 실패 및 재시도 시나리오:
     * 메시지 발행이 실패하면(TestPublisher가 예외 발생),
     * 해당 메시지는 다시 PENDING 상태로 돌아가야 하며,
     * 재시도 횟수(retry_count)가 증가하고 다음 재시도 시간(next_retry_at)이 설정되어야 한다.
     */
    @Test
    @DisplayName("메시지 발행 실패 시 재시도를 위해 상태를 PENDING으로 변경하고 재시도 정보를 업데이트한다")
    void 메시지_발행_실패_시_재시도를_위해_상태를_PENDING으로_변경하고_재시도_정보를_업데이트한다() {
        // given
        OutboxMessage m = createMessage("REQ-FAIL", "{\"val\":\"fail\"}");

        // 발행 실패 설정
        testPublisher.failNext(true);

        // when
        List<OutboxMessage> claimed = poller.pollOnce();
        processor.process(claimed);

        // then
        Optional<OutboxMessage> reloaded = repo.findById(m.getId());
        assertThat(reloaded).isPresent();

        OutboxMessage msg = reloaded.get();
        assertThat(msg.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(msg.getRetryCount()).isEqualTo(1);
        assertThat(msg.getNextRetryAt()).isAfter(OffsetDateTime.now(ZoneOffset.UTC));
        assertThat(msg.getLastError()).contains("Test exception");
    }

    private OutboxMessage createMessage(String reqId, String payload) {
        return repo.upsertReturning(
                "AUTH_ERROR",
                reqId,
                "AUTH_ERROR_DETECTED_V1",
                payload,
                "AUTH_ERROR:" + reqId + ":AUTH_ERROR_DETECTED_V1"
        );
    }
}
