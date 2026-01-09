package com.yunhwan.auth.error.outbox.support.api;

/**
 * 멱등키 결정 정책을 분리하기 위한 인터페이스.
 * 도메인별로 구현체를 두고 주입해서 사용한다.
 */
@FunctionalInterface
public interface IdempotencyKeyResolver<T> {
    String resolve(T source);
}
