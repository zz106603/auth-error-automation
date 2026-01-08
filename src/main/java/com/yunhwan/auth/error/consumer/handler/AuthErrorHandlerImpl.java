package com.yunhwan.auth.error.consumer.handler;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
public class AuthErrorHandlerImpl implements AuthErrorHandler {

    @Override
    public void handle(String payload, Map<String, Object> headers) {
        // 지금은 비즈니스 처리 대신 최소 로그만
        log.info("[AuthErrorHandler] handle called. headers={}, payload={}", headers, payload);
    }
}
