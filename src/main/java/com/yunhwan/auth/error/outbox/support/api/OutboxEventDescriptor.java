package com.yunhwan.auth.error.outbox.support.api;

/**
 * Outbox에 적재되는 이벤트의 메타 정보를 정의한다.
 * (aggregateType, eventType, version 등)
 */
public interface OutboxEventDescriptor {

    String aggregateType();

    String eventType();

    default String version() {
        return "v1";
    }
}
