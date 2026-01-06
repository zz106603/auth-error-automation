package com.yunhwan.auth.error.outbox.service;

import com.yunhwan.auth.error.domain.outbox.OutboxMessage;
import com.yunhwan.auth.error.domain.outbox.OutboxStatus;
import com.yunhwan.auth.error.outbox.persistence.OutboxMessageRepository;
import com.yunhwan.auth.error.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Outbox Poller 통합 테스트")
class OutboxPollerIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    OutboxMessageRepository outboxMessageRepo;
    @Autowired
    OutboxPoller outboxPoller;
    @Autowired
    TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        outboxMessageRepo.deleteAll();
    }

    /**
     * 기본 기능 검증:
     * PENDING 상태의 메시지가 있을 때 pollOnce()를 호출하면
     * 해당 메시지를 가져오고 상태를 PROCESSING으로 변경해야 한다.
     * 또한, 점유한 인스턴스 정보(owner)와 시작 시간도 기록되어야 한다.
     */
    @Test
    @DisplayName("폴링 시 대기중(PENDING)인 메시지를 점유하고 처리중(PROCESSING) 상태로 변경한다")
    void 폴링_시_대기중인_메시지를_점유하고_처리중_상태로_변경한다() {
        // given
        OutboxMessage inserted = createMessage("REQ-1", "{\"reason\":\"token expired\"}");

        // when
        List<OutboxMessage> claimed = outboxPoller.pollOnce();

        // then
        assertThat(claimed).hasSize(1);
        OutboxMessage m = claimed.get(0);

        assertThat(m.getId()).isEqualTo(inserted.getId());
        assertThat(m.getStatus()).isEqualTo(OutboxStatus.PROCESSING);
        assertThat(m.getProcessingOwner()).isNotBlank();
        assertThat(m.getProcessingStartedAt()).isNotNull();

        // DB에서도 PROCESSING 반영 확인
        OutboxMessage reloaded = outboxMessageRepo.findById(inserted.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(OutboxStatus.PROCESSING);
        assertThat(reloaded.getProcessingOwner()).isNotBlank();
        assertThat(reloaded.getProcessingStartedAt()).isNotNull();
    }

    /**
     * 재시도 스케줄링 검증:
     * next_retry_at 값이 현재 시간보다 미래인 메시지는
     * 아직 처리할 시점이 아니므로 가져오면 안 된다.
     */
    @Test
    @DisplayName("재시도 시간이 미래인 메시지는 폴링하지 않는다")
    void 재시도_시간이_미래인_메시지는_폴링하지_않는다() {
        // given: 2건 넣고, 하나는 미래로 업데이트
        OutboxMessage m1 = createMessage("REQ-1", "{\"a\":1}");
        OutboxMessage m2 = createMessage("REQ-2", "{\"a\":2}");

        // REQ-2는 next_retry_at을 미래로 밀어두기 (10분 뒤)
        OffsetDateTime future = OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(10);
        tx.executeWithoutResult(status -> outboxMessageRepo.setNextRetryAt(m2.getId(), future));

        // when
        List<OutboxMessage> claimed = outboxPoller.pollOnce();

        // then: REQ-1만 잡혀야 함
        assertThat(claimed).hasSize(1);
        assertThat(claimed.get(0).getId()).isEqualTo(m1.getId());

        OutboxMessage reloaded1 = outboxMessageRepo.findById(m1.getId()).orElseThrow();
        OutboxMessage reloaded2 = outboxMessageRepo.findById(m2.getId()).orElseThrow();

        assertThat(reloaded1.getStatus()).isEqualTo(OutboxStatus.PROCESSING);
        assertThat(reloaded2.getStatus()).isEqualTo(OutboxStatus.PENDING);
    }

    /**
     * 동시성 제어 검증 (핵심):
     * 여러 Poller 인스턴스(스레드)가 동시에 실행되더라도
     * 동일한 메시지를 중복으로 가져가면 안 된다. (SKIP LOCKED 동작 확인)
     */
    @Test
    @DisplayName("동시에 여러 폴러가 실행되어도 동일한 메시지를 중복으로 점유하지 않는다")
    void 동시에_여러_폴러가_실행되어도_동일한_메시지를_중복으로_점유하지_않는다() throws Exception {
        // given: PENDING 메시지 20건 생성
        int total = 20;
        for (int i = 1; i <= total; i++) {
            createMessage("REQ-" + i, "{\"i\":" + i + "}");
        }

        // when: pollOnce를 동시에 2개의 스레드에서 실행
        ExecutorService es = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        Callable<List<Long>> task = () -> {
            ready.countDown(); // 준비 완료 신호
            start.await(3, TimeUnit.SECONDS); // 시작 신호 대기
            return outboxPoller.pollOnce().stream().map(OutboxMessage::getId).toList();
        };

        Future<List<Long>> f1 = es.submit(task);
        Future<List<Long>> f2 = es.submit(task);

        ready.await(3, TimeUnit.SECONDS); // 모든 스레드가 준비될 때까지 대기
        start.countDown(); // 동시에 시작

        List<Long> ids1 = f1.get(5, TimeUnit.SECONDS);
        List<Long> ids2 = f2.get(5, TimeUnit.SECONDS);

        es.shutdownNow();

        // then: 두 스레드가 가져간 메시지 목록에 교집합이 없어야 함
        Set<Long> s1 = new HashSet<>(ids1);
        Set<Long> s2 = new HashSet<>(ids2);

        Set<Long> intersection = new HashSet<>(s1);
        intersection.retainAll(s2);

        assertThat(intersection).as("중복으로 점유된 메시지가 없어야 합니다").isEmpty();

        // 합집합 크기는 0보다 커야 함 (적어도 하나는 가져갔어야 함)
        Set<Long> union = new HashSet<>();
        union.addAll(s1);
        union.addAll(s2);

        assertThat(union.size()).isGreaterThan(0);

        // 가져간 메시지들은 DB에서 모두 PROCESSING 상태여야 함
        List<OutboxMessage> claimedRows = outboxMessageRepo.findAllById(union);
        assertThat(claimedRows).allSatisfy(m -> assertThat(m.getStatus()).isEqualTo(OutboxStatus.PROCESSING));
    }

    /**
     * 상태 필터링 검증:
     * 이미 다른 프로세스에 의해 PROCESSING 상태가 된 메시지는
     * 다시 폴링 대상이 되어서는 안 된다.
     */
    @Test
    @DisplayName("이미 처리중(PROCESSING)인 메시지는 다시 폴링하지 않는다")
    void 이미_처리중인_메시지는_다시_폴링하지_않는다() {
        // given
        OutboxMessage inserted = createMessage("REQ-1", "{\"a\":1}");

        // first claim: 첫 번째 폴링으로 상태를 PROCESSING으로 변경
        List<OutboxMessage> first = outboxPoller.pollOnce();
        assertThat(first).hasSize(1);

        // when: second claim (다시 폴링 시도)
        List<OutboxMessage> second = outboxPoller.pollOnce();

        // then: 이미 처리 중이므로 가져오지 않아야 함
        assertThat(second).isEmpty();

        OutboxMessage reloaded = outboxMessageRepo.findById(inserted.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(OutboxStatus.PROCESSING);
    }

    private OutboxMessage createMessage(String reqId, String payload) {
        return outboxMessageRepo.upsertReturning(
                "AUTH_ERROR",
                reqId,
                "AUTH_ERROR_DETECTED_V1",
                payload,
                "AUTH_ERROR:" + reqId + ":AUTH_ERROR_DETECTED_V1"
        );
    }
}
