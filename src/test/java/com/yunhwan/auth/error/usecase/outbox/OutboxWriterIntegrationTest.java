package com.yunhwan.auth.error.usecase.outbox;

import com.yunhwan.auth.error.domain.outbox.OutboxStatus;
import com.yunhwan.auth.error.domain.outbox.descriptor.OutboxEventDescriptor;
import com.yunhwan.auth.error.testsupport.base.AbstractStubIntegrationTest;
import com.yunhwan.auth.error.usecase.outbox.port.OutboxMessageStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

/**
 * Outbox Writer의 메시지 적재 및 멱등성 보장 로직을 검증하는 통합 테스트.
 * <p>
 * 1. 메시지 적재: 요청 시 DB에 PENDING 상태로 정상 저장되어야 함.
 * 2. 멱등성 보장: 동일한 멱등성 키(Idempotency Key)로 중복 요청 시, 새로운 데이터를 생성하지 않고 기존 데이터를 반환해야 함.
 */
@DisplayName("Outbox Writer 통합 테스트")
class OutboxWriterIntegrationTest extends AbstractStubIntegrationTest {

    @Autowired
    OutboxWriter outboxWriter;

    @Autowired
    OutboxMessageStore outboxMessageStore;

    @Test
    @DisplayName("메시지 적재 요청 시 DB에 정상적으로 저장된다")
    void 메시지_적재_요청_시_DB에_정상적으로_저장된다() {
        // Given: 테스트용 데이터 준비
        String reqId = "REQ-1-" + UUID.randomUUID();
        String aggregateId = newTestScope() + "-" + reqId;

        OutboxEventDescriptor<TestPayload> descriptor = testDescriptor("AUTH_ERROR", "AUTH_ERROR_DETECTED_V1");
        TestPayload payload = new TestPayload("hello", "world");

        // When: 메시지 적재 요청
        var saved = outboxWriter.enqueue(descriptor, aggregateId, payload);

        // Then: 저장된 메시지 검증
        assertThat(saved.getId())
                .withFailMessage("저장된 메시지의 ID는 null이 아니어야 합니다.")
                .isNotNull();

        // DB에서 조회하여 상태 확인
        String idemKey = descriptor.idempotencyKey(payload);
        var found = outboxMessageStore.findByIdempotencyKey(idemKey);

        assertThat(found)
                .withFailMessage("멱등성 키로 메시지가 조회되어야 합니다.")
                .isPresent();
        assertThat(found.get().getStatus())
                .withFailMessage("초기 상태는 PENDING이어야 합니다.")
                .isEqualTo(OutboxStatus.PENDING);
    }

    @Test
    @DisplayName("동일한 멱등성 키로 중복 요청 시 새로운 로우를 생성하지 않고 기존 메시지를 반환한다")
    void 동일한_멱등성_키로_중복_요청_시_새로운_로우를_생성하지_않고_기존_메시지를_반환한다() {
        // Given: 테스트용 데이터 준비
        String reqId = "REQ-2-" + UUID.randomUUID();
        String aggregateId = newTestScope() + "-" + reqId;

        OutboxEventDescriptor<TestPayload> descriptor = testDescriptor("AUTH_ERROR", "AUTH_ERROR_DETECTED_V1");
        TestPayload payload = new TestPayload("x", "1");

        // When: 동일한 데이터로 두 번 적재 요청
        var first = outboxWriter.enqueue(descriptor, aggregateId, payload);
        var second = outboxWriter.enqueue(descriptor, aggregateId, payload);

        // Then: 두 번째 요청 결과는 첫 번째와 동일한 ID를 가져야 함 (중복 생성 방지)
        assertThat(second.getId())
                .withFailMessage("중복 요청 시 동일한 ID가 반환되어야 합니다.")
                .isEqualTo(first.getId());

        // Then: DB 조회 시에도 해당 ID로 조회되어야 함
        String idemKey = descriptor.idempotencyKey(payload);
        var found = outboxMessageStore.findByIdempotencyKey(idemKey);

        assertThat(found).isPresent();
        assertThat(found.get().getId())
                .withFailMessage("DB에 저장된 ID도 반환된 ID와 일치해야 합니다.")
                .isEqualTo(first.getId());
    }

    /**
     * 테스트용 Descriptor 생성 헬퍼 메서드.
     * <p>
     * 테스트 목적상 Idempotency Key는 "aggregateType:eventType:payloadKey:payloadValue" 조합으로 단순화하여 생성한다.
     */
    private OutboxEventDescriptor<TestPayload> testDescriptor(String aggregateType, String eventType) {
        return new OutboxEventDescriptor<>() {
            @Override
            public String aggregateType() {
                return aggregateType;
            }

            @Override
            public String eventType() {
                return eventType;
            }

            @Override
            public String idempotencyKey(TestPayload payload) {
                // 테스트에서는 "payload 내용"만 같으면 같은 키가 되도록 단순화
                return aggregateType + ":" + eventType + ":" + payload.k() + ":" + payload.v();
            }
        };
    }

    private record TestPayload(String k, String v) {}
}
