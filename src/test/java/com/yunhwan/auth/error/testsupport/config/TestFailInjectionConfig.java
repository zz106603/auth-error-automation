package com.yunhwan.auth.error.testsupport.config;

import com.yunhwan.auth.error.common.exception.RetryableAuthErrorException;
import com.yunhwan.auth.error.usecase.consumer.handler.AuthErrorHandler;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

@TestConfiguration
public class TestFailInjectionConfig {

    @Bean
    @Primary
    public AuthErrorHandler failInjectedAuthErrorHandler(
            @Qualifier("authErrorHandlerImpl") AuthErrorHandler realHandler
    ) {
        return new FailInjectedAuthErrorHandler(realHandler);
    }

    public static class FailInjectedAuthErrorHandler implements AuthErrorHandler {
        private final AuthErrorHandler delegate;
        private final AtomicInteger failRemaining = new AtomicInteger(0);
        private volatile boolean alwaysFail = false;

        public FailInjectedAuthErrorHandler(AuthErrorHandler delegate) {
            this.delegate = delegate;
        }

        public void failFirst(int n) {
            alwaysFail = false;
            failRemaining.set(n);
        }

        public void failAlways() {
            alwaysFail = true;
        }

        @Override
        public void handle(String payloadJson, Map<String, Object> headers) {
            if (alwaysFail) throw new RetryableAuthErrorException("injected");
            int prev = failRemaining.getAndUpdate(x -> Math.max(0, x - 1));
            if (prev > 0) throw new RetryableAuthErrorException("injected");

            delegate.handle(payloadJson, headers);
        }
    }
}
