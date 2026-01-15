package com.yunhwan.auth.error.usecase.outbox.port;

public interface OutboxScopeResolver {
    /**
     * null 이면 스코프 필터링 없이 조회/처리
     */
    String scopePrefixOrNull();
    void set(String prefix);
    void clear();
}
