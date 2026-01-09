package com.yunhwan.auth.error.outbox.service;

import com.yunhwan.auth.error.domain.outbox.OutboxMessage;
import com.yunhwan.auth.error.domain.outbox.OutboxStatus;
import com.yunhwan.auth.error.outbox.persistence.OutboxMessageRepository;
import com.yunhwan.auth.error.support.AbstractIntegrationTest;
import com.yunhwan.auth.error.support.AbstractStubIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Outbox 정상 처리 통합 테스트")
class OutboxSuccessIntegrationTest extends AbstractStubIntegrationTest {

    @Autowired
    OutboxMessageRepository outboxMessageRepo;

    @Autowired
    OutboxPoller outboxPoller;

    @Autowired
    Clock clock;

    @Autowired
    OutboxProcessor outboxProcessor;

    @BeforeEach
    void setUp() {
        outboxMessageRepo.deleteAll();
    }

    /**
     * Happy Path 검증:
     * 1. 메시지가 DB에 적재된다.
     * 2. Poller가 메시지를 가져온다.
     * 3. Processor가 메시지를 발행하고 성공 처리한다.
     * 4. 최종 상태가 PUBLISHED가 된다.
     */
    @Test
    @DisplayName("메시지가 정상적으로 생성, 폴링, 발행되어 PUBLISHED 상태가 된다")
    void 메시지가_정상적으로_생성_폴링_발행되어_PUBLISHED_상태가_된다() {
        // given: DB에 outbox row 생성
        OutboxMessage saved = createMessage("REQ-1" + UUID.randomUUID(), "{ \"error\": \"AUTH_FAILED\" }");

        // when: 폴링 및 처리
        List<OutboxMessage> claimed = outboxPoller.pollOnce();
        outboxProcessor.process(claimed);

        // then: 최종 상태 검증
        OutboxMessage after = outboxMessageRepo.findById(saved.getId()).orElseThrow();

        assertThat(after.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);

        // 성공 후 필드 정리 확인
        assertThat(after.getProcessingOwner()).isNull();
        assertThat(after.getProcessingStartedAt()).isNull();
        assertThat(after.getLastError()).isNull();
        assertThat(after.getNextRetryAt()).isNull();
    }

    private OutboxMessage createMessage(String reqId, String payload) {
        return outboxMessageRepo.upsertReturning(
                "AUTH_ERROR",
                reqId,
                "AUTH_ERROR_DETECTED_V1",
                payload,
                "AUTH_ERROR:" + reqId + ":V1",
                OffsetDateTime.now(clock)
        );
    }
}
