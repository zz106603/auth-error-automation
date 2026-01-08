package com.yunhwan.auth.error.consumer;

import com.yunhwan.auth.error.consumer.handler.AuthErrorHandler;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 통합 테스트용 AuthErrorHandler 구현체입니다.
 * <p>
 * 실제 비즈니스 로직을 수행하는 대신, 메서드 호출 횟수만 기록하여
 * 멱등성 테스트 등에서 핸들러가 몇 번 호출되었는지 검증하는 용도로 사용됩니다.
 */
@Component
@Primary // 테스트 환경에서 실제 구현체 대신 이 빈이 우선적으로 주입됩니다.
public class TestAuthErrorHandler implements AuthErrorHandler {

    // 멀티스레드 환경(RabbitMQ Consumer)에서도 안전하게 카운팅하기 위해 AtomicInteger 사용
    private final AtomicInteger callCount = new AtomicInteger(0);

    @Override
    public void handle(String payload, Map<String, Object> headers) {
        // 실제 로직 없이 호출 횟수만 증가
        callCount.incrementAndGet();
    }

    /**
     * 현재까지의 호출 횟수를 반환합니다.
     */
    public int getCallCount() {
        return callCount.get();
    }

    /**
     * 테스트 간 간섭을 방지하기 위해 카운터를 초기화합니다.
     * (@BeforeEach 등에서 호출)
     */
    public void reset() {
        callCount.set(0);
    }
}
