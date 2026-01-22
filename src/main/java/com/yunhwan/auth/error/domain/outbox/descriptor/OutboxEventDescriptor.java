package com.yunhwan.auth.error.domain.outbox.descriptor;

/**
 * Outbox 이벤트 계약(Contract):
 * - aggregateType: 도메인/집계 종류
 * - eventType: wire contract (권장: v1 포함)
 * - idempotencyKey: 동일 이벤트 중복 적재 방지용 키
 */
public interface OutboxEventDescriptor<T> {

    String aggregateType();

    /** 권장: "auth.error.recorded.v1" 처럼 버전 포함 */
    String eventType();

    String idempotencyKey(T payload);
}
