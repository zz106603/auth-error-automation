package com.yunhwan.auth.error.usecase.outbox;

import com.yunhwan.auth.error.testsupport.base.AbstractStubIntegrationTest;
import com.yunhwan.auth.error.usecase.outbox.dto.OutboxEnqueueCommand;
import com.yunhwan.auth.error.usecase.outbox.port.OutboxMessageStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

@DisplayName("Outbox Writer 통합 테스트")
class OutboxWriterIntegrationTest extends AbstractStubIntegrationTest {

    @Autowired
    OutboxWriter outboxWriter;
    @Autowired
    OutboxMessageStore outboxMessageStore;

    /**
     * 정상 적재 검증:
     * OutboxEnqueueCommand를 받아 DB에 정상적으로 저장되는지 확인한다.
     * 저장된 메시지는 PENDING 상태여야 한다.
     */
    @Test
    @DisplayName("메시지 적재 요청 시 DB에 정상적으로 저장된다")
    void 메시지_적재_요청_시_DB에_정상적으로_저장된다() {
        // given
        OutboxEnqueueCommand cmd = createCommand("REQ-1" + UUID.randomUUID(), "{\"hello\":\"world\"}");

        // when
        var saved = outboxWriter.enqueue(cmd);

        // then
        assertThat(saved.getId()).isNotNull();

        var found = outboxMessageStore.findByIdempotencyKey(cmd.idempotencyKey());
        assertThat(found).isPresent();
        assertThat(found.get().getStatus().name()).isEqualTo("PENDING");
    }

    /**
     * 멱등성 검증:
     * 동일한 멱등성 키(idempotencyKey)를 가진 요청이 중복해서 들어오더라도
     * DB에는 단 하나의 로우만 존재해야 한다.
     * 두 번째 요청에서도 에러 없이 기존에 저장된 메시지를 반환해야 한다.
     */
    @Test
    @DisplayName("동일한 멱등성 키로 중복 요청 시 새로운 로우를 생성하지 않고 기존 메시지를 반환한다")
    void 동일한_멱등성_키로_중복_요청_시_새로운_로우를_생성하지_않고_기존_메시지를_반환한다() {
        // given
        OutboxEnqueueCommand cmd = createCommand("REQ-2" + UUID.randomUUID(), "{\"x\":1}");

        // when
        var first = outboxWriter.enqueue(cmd);
        var second = outboxWriter.enqueue(cmd);

        // then: 같은 row를 반환해야 함
        assertThat(second.getId()).isEqualTo(first.getId());

        // then: idempotencyKey로 조회되는 row도 그 ID여야 함
        var found = outboxMessageStore.findByIdempotencyKey(cmd.idempotencyKey());
        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(first.getId());
    }

    private OutboxEnqueueCommand createCommand(String reqId, String payload) {
        String scopedReqId = newTestScope() + "-" + reqId;
        return new OutboxEnqueueCommand(
                "AUTH_ERROR",
                scopedReqId,
                "AUTH_ERROR_DETECTED_V1",
                payload,
                "AUTH_ERROR:" + scopedReqId + ":AUTH_ERROR_DETECTED_V1"
        );
    }
}
