package com.yunhwan.auth.error.testsupport.config;

import com.yunhwan.auth.error.common.exception.RetryableAuthErrorException;
import com.yunhwan.auth.error.usecase.consumer.handler.AuthErrorHandler;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

@TestConfiguration
public class TestFailInjectionConfig {

    @Bean
    public FailInjectedAuthErrorHandler recordedFailInjector() {
        return new FailInjectedAuthErrorHandler();
    }

    @Bean
    public FailInjectedAuthErrorHandler analysisFailInjector() {
        return new FailInjectedAuthErrorHandler();
    }

    @Bean
    public BeanPostProcessor failInjectingPostProcessor(
            FailInjectedAuthErrorHandler recordedFailInjector,
            FailInjectedAuthErrorHandler analysisFailInjector
    ) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (!(bean instanceof AuthErrorHandler handler)) return bean;

                if ("authErrorRecordedHandler".equals(beanName)) {
                    return recordedFailInjector.wrap(handler);
                }

                if ("authErrorAnalysisRequestedHandler".equals(beanName)) {
                    return analysisFailInjector.wrap(handler);
                }

                return bean;
            }
        };
    }

    /**
     * 테스트에서 주입받아 제어하는 실패 주입기
     */
    public static class FailInjectedAuthErrorHandler implements AuthErrorHandler {
        private volatile AuthErrorHandler delegate;
        private final AtomicInteger failRemaining = new AtomicInteger(0);
        private volatile boolean alwaysFail = false;

        public AuthErrorHandler wrap(AuthErrorHandler delegate) {
            this.delegate = delegate;
            return this;
        }

        public void reset() {
            alwaysFail = false;
            failRemaining.set(0);
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
            if (delegate == null) {
                throw new IllegalStateException("delegate is not set. wrap() must be called by BeanPostProcessor.");
            }

            if (alwaysFail) throw new RetryableAuthErrorException("injected");
            int prev = failRemaining.getAndUpdate(x -> Math.max(0, x - 1));
            if (prev > 0) throw new RetryableAuthErrorException("injected");

            delegate.handle(payloadJson, headers);
        }
    }
}
