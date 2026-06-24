package com.yunhwan.auth.error.usecase.outbox;

import com.yunhwan.auth.error.common.exception.OutboxPayloadMismatchException;
import com.yunhwan.auth.error.domain.outbox.OutboxStatus;
import com.yunhwan.auth.error.infra.autherror.outbox.AuthErrorAnalysisRequestedEventDescriptor;
import com.yunhwan.auth.error.infra.autherror.outbox.AuthErrorRecordedEventDescriptor;
import com.yunhwan.auth.error.testsupport.base.AbstractStubIntegrationTest;
import com.yunhwan.auth.error.usecase.autherror.dto.AuthErrorAnalysisRequestedPayload;
import com.yunhwan.auth.error.usecase.autherror.dto.AuthErrorRecordedPayload;
import com.yunhwan.auth.error.usecase.outbox.port.OutboxMessageStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;

/**
 * Outbox Writer의 메시지 적재 및 멱등성 보장 로직을 검증하는 통합 테스트.
 * <p>
 * 1. 메시지 적재: 요청 시 DB에 PENDING 상태로 정상 저장되어야 함.
 * 2. 멱등성 보장: 동일한 멱등성 키(Idempotency Key)로 중복 요청 시, 새로운 데이터를 생성하지 않고 기존 데이터를 반환해야 함.
 */
@DisplayName("[TS-03] [TS-04] Outbox Writer 통합 테스트")
class OutboxWriterIntegrationTest extends AbstractStubIntegrationTest {

    @Autowired
    OutboxWriter outboxWriter;

    @Autowired
    OutboxMessageStore outboxMessageStore;

    @Autowired
    AuthErrorRecordedEventDescriptor authErrorRecordedEventDescriptor;

    @Autowired
    AuthErrorAnalysisRequestedEventDescriptor authErrorAnalysisRequestedEventDescriptor;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("[TS-03] 메시지 적재 요청 시 DB에 정상적으로 저장된다")
    void 메시지_적재_요청_시_DB에_정상적으로_저장된다() {
        // Given: 테스트용 데이터 준비
        long authErrorId = Math.abs(UUID.randomUUID().getMostSignificantBits());
        String requestId = "REQ-1-" + UUID.randomUUID();
        OffsetDateTime occurredAt = OffsetDateTime.now();
        String aggregateId = String.valueOf(authErrorId);

        AuthErrorRecordedPayload payload = new AuthErrorRecordedPayload(authErrorId, requestId, occurredAt, occurredAt);

        // When: 메시지 적재 요청
        var saved = outboxWriter.enqueue(authErrorRecordedEventDescriptor, aggregateId, payload);

        // Then: 저장된 메시지 검증
        assertThat(saved.getId())
                .withFailMessage("저장된 메시지의 ID는 null이 아니어야 합니다.")
                .isNotNull();

        // DB에서 조회하여 상태 확인
        String idemKey = authErrorRecordedEventDescriptor.idempotencyKey(payload);
        var found = outboxMessageStore.findByIdempotencyKey(idemKey);

        assertThat(found)
                .withFailMessage("멱등성 키로 메시지가 조회되어야 합니다.")
                .isPresent();
        assertThat(found.get().getStatus())
                .withFailMessage("초기 상태는 PENDING이어야 합니다.")
                .isEqualTo(OutboxStatus.PENDING);
        assertThat(found.get().getIdempotencyKey())
                .withFailMessage("멱등성 키는 정책 형식(auth_error:recorded:{authErrorId})이어야 합니다.")
                .isEqualTo("auth_error:recorded:" + authErrorId);
        assertThat(found.get().getPayloadHash())
                .withFailMessage("Outbox payload hash는 신규 메시지 저장 시 함께 기록되어야 합니다.")
                .isNotBlank();
    }

    @Test
    @DisplayName("[TS-03] 동일한 멱등성 키로 중복 요청 시 새로운 로우를 생성하지 않고 기존 메시지를 반환한다")
    void 동일한_멱등성_키로_중복_요청_시_새로운_로우를_생성하지_않고_기존_메시지를_반환한다() {
        // Given: 테스트용 데이터 준비
        long authErrorId = Math.abs(UUID.randomUUID().getMostSignificantBits());
        String requestId = "REQ-2-" + UUID.randomUUID();
        OffsetDateTime occurredAt = OffsetDateTime.now();
        String aggregateId = String.valueOf(authErrorId);
        AuthErrorRecordedPayload payload = new AuthErrorRecordedPayload(authErrorId, requestId, occurredAt, occurredAt);

        // When: 동일한 데이터로 두 번 적재 요청
        var first = outboxWriter.enqueue(authErrorRecordedEventDescriptor, aggregateId, payload);
        var second = outboxWriter.enqueue(authErrorRecordedEventDescriptor, aggregateId, payload);

        // Then: 두 번째 요청 결과는 첫 번째와 동일한 ID를 가져야 함 (중복 생성 방지)
        assertThat(second.getId())
                .withFailMessage("중복 요청 시 동일한 ID가 반환되어야 합니다.")
                .isEqualTo(first.getId());

        // Then: DB 조회 시에도 해당 ID로 조회되어야 함
        String idemKey = authErrorRecordedEventDescriptor.idempotencyKey(payload);
        var found = outboxMessageStore.findByIdempotencyKey(idemKey);

        assertThat(found).isPresent();
        assertThat(found.get().getId())
                .withFailMessage("DB에 저장된 ID도 반환된 ID와 일치해야 합니다.")
                .isEqualTo(first.getId());
        assertThat(countByIdempotencyKey(idemKey))
                .withFailMessage("멱등성 키 기준으로 outbox_message는 1건만 존재해야 합니다.")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("[TS-03] recorded 이벤트는 동일 멱등키에 다른 payload가 들어오면 계약 위반으로 실패한다")
    void recorded_동일_멱등키_다른_payload는_실패한다() {
        long authErrorId = Math.abs(UUID.randomUUID().getMostSignificantBits());
        OffsetDateTime occurredAt = OffsetDateTime.now();
        String aggregateId = String.valueOf(authErrorId);
        AuthErrorRecordedPayload firstPayload = new AuthErrorRecordedPayload(
                authErrorId,
                "REQ-RECORDED-A-" + UUID.randomUUID(),
                occurredAt,
                occurredAt
        );
        AuthErrorRecordedPayload changedPayload = new AuthErrorRecordedPayload(
                authErrorId,
                "REQ-RECORDED-B-" + UUID.randomUUID(),
                occurredAt,
                occurredAt.plusSeconds(1)
        );

        var first = outboxWriter.enqueue(authErrorRecordedEventDescriptor, aggregateId, firstPayload);
        String idemKey = authErrorRecordedEventDescriptor.idempotencyKey(firstPayload);

        assertThatThrownBy(() -> outboxWriter.enqueue(authErrorRecordedEventDescriptor, aggregateId, changedPayload))
                .isInstanceOf(OutboxPayloadMismatchException.class)
                .hasMessageContaining(idemKey);

        assertThat(countByIdempotencyKey(idemKey))
                .withFailMessage("payload mismatch가 발생해도 기존 outbox_message 외 추가 row가 생기면 안 됩니다.")
                .isEqualTo(1L);
        assertThat(outboxMessageStore.findByIdempotencyKey(idemKey).orElseThrow().getId())
                .withFailMessage("payload mismatch 후에도 기존 메시지가 보존되어야 합니다.")
                .isEqualTo(first.getId());
    }

    @Test
    @DisplayName("[TS-04] analysis_requested 이벤트도 authErrorId 기준 멱등키로 1건만 저장된다")
    void analysis_requested_멱등성_키_및_단일_로우_보장() {
        long authErrorId = Math.abs(UUID.randomUUID().getMostSignificantBits());
        String requestId = "REQ-3-" + UUID.randomUUID();
        OffsetDateTime occurredAt = OffsetDateTime.now();
        OffsetDateTime requestedAt = occurredAt.plusSeconds(1);
        String aggregateId = String.valueOf(authErrorId);

        AuthErrorAnalysisRequestedPayload payload = new AuthErrorAnalysisRequestedPayload(
                authErrorId, requestId, occurredAt, requestedAt
        );

        var first = outboxWriter.enqueue(authErrorAnalysisRequestedEventDescriptor, aggregateId, payload);
        var second = outboxWriter.enqueue(authErrorAnalysisRequestedEventDescriptor, aggregateId, payload);

        assertThat(second.getId())
                .withFailMessage("중복 요청 시 동일한 ID가 반환되어야 합니다.")
                .isEqualTo(first.getId());

        String idemKey = authErrorAnalysisRequestedEventDescriptor.idempotencyKey(payload);
        var found = outboxMessageStore.findByIdempotencyKey(idemKey);
        assertThat(found)
                .withFailMessage("멱등성 키로 메시지가 조회되어야 합니다.")
                .isPresent();
        assertThat(found.get().getIdempotencyKey())
                .withFailMessage("멱등성 키는 정책 형식(auth_error:analysis_requested:{authErrorId})이어야 합니다.")
                .isEqualTo("auth_error:analysis_requested:" + authErrorId);

        assertThat(countByIdempotencyKey(idemKey))
                .withFailMessage("멱등성 키 기준으로 outbox_message는 1건만 존재해야 합니다.")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("[TS-04] analysis_requested 이벤트도 동일 멱등키에 다른 payload가 들어오면 계약 위반으로 실패한다")
    void analysis_requested_동일_멱등키_다른_payload는_실패한다() {
        long authErrorId = Math.abs(UUID.randomUUID().getMostSignificantBits());
        OffsetDateTime occurredAt = OffsetDateTime.now();
        String aggregateId = String.valueOf(authErrorId);
        AuthErrorAnalysisRequestedPayload firstPayload = new AuthErrorAnalysisRequestedPayload(
                authErrorId,
                "REQ-ANALYSIS-A-" + UUID.randomUUID(),
                occurredAt,
                occurredAt.plusSeconds(1)
        );
        AuthErrorAnalysisRequestedPayload changedPayload = new AuthErrorAnalysisRequestedPayload(
                authErrorId,
                "REQ-ANALYSIS-B-" + UUID.randomUUID(),
                occurredAt,
                occurredAt.plusSeconds(2)
        );

        var first = outboxWriter.enqueue(authErrorAnalysisRequestedEventDescriptor, aggregateId, firstPayload);
        String idemKey = authErrorAnalysisRequestedEventDescriptor.idempotencyKey(firstPayload);

        assertThatThrownBy(() -> outboxWriter.enqueue(authErrorAnalysisRequestedEventDescriptor, aggregateId, changedPayload))
                .isInstanceOf(OutboxPayloadMismatchException.class)
                .hasMessageContaining(idemKey);

        assertThat(countByIdempotencyKey(idemKey))
                .withFailMessage("payload mismatch가 발생해도 기존 outbox_message 외 추가 row가 생기면 안 됩니다.")
                .isEqualTo(1L);
        assertThat(outboxMessageStore.findByIdempotencyKey(idemKey).orElseThrow().getId())
                .withFailMessage("payload mismatch 후에도 기존 메시지가 보존되어야 합니다.")
                .isEqualTo(first.getId());
    }

    private long countByIdempotencyKey(String idempotencyKey) {
        Long count = jdbcTemplate.queryForObject(
                "select count(*) from outbox_message where idempotency_key = ?",
                Long.class,
                idempotencyKey
        );
        return count == null ? 0L : count;
    }
}
