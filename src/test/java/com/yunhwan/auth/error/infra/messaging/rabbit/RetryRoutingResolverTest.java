package com.yunhwan.auth.error.infra.messaging.rabbit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 재시도 횟수에 따른 라우팅 키 결정 로직을 검증하는 단위 테스트.
 * <p>
 * 설정된 임계값(fastMax, mediumMax)에 따라
 * 10초(10s), 1분(1m), 10분(10m) 대기 큐 중 어디로 라우팅될지 결정한다.
 * 또한, 이벤트 타입(Recorded, AnalysisRequested)에 따라 적절한 Exchange를 반환하는지도 확인한다.
 */
class RetryRoutingResolverTest {

    // 테스트를 위한 설정값 주입: fastMax=1, mediumMax=1
    // 즉, 1회차 -> 10s, 2회차 이상 -> 10m (1m 구간 생략됨)
    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withUserConfiguration(TestConfig.class)
                    .withPropertyValues(
                            "auth-error.rabbit.retry.fast-max=1",
                            "auth-error.rabbit.retry.medium-max=1"
                    );

    @Test
    @DisplayName("Recorded 이벤트: 재시도 1회차는 10초 대기 큐로 라우팅되어야 한다")
    void Recorded_이벤트_1회차_재시도_10초_큐_라우팅() {
        contextRunner.run(ctx -> {
            RetryRoutingResolver resolver = ctx.getBean(RetryRoutingResolver.class);

            String rk = resolver.resolve(RabbitTopologyConfig.RK_RECORDED, 1);

            assertThat(rk).isEqualTo(RabbitTopologyConfig.RETRY_RK_RECORDED_10S);
        });
    }

    @Test
    @DisplayName("Recorded 이벤트: 재시도 2회차는 (설정에 따라) 10분 대기 큐로 라우팅되어야 한다")
    void Recorded_이벤트_2회차_재시도_10분_큐_라우팅() {
        contextRunner.run(ctx -> {
            RetryRoutingResolver resolver = ctx.getBean(RetryRoutingResolver.class);

            // fastMax=1, mediumMax=1 이므로 2회차는 바로 10m 구간으로 진입
            String rk = resolver.resolve(RabbitTopologyConfig.RK_RECORDED, 2);

            assertThat(rk).isEqualTo(RabbitTopologyConfig.RETRY_RK_RECORDED_10M);
        });
    }

    @Test
    @DisplayName("AnalysisRequested 이벤트: 재시도 1회차는 10초 대기 큐로 라우팅되어야 한다")
    void AnalysisRequested_이벤트_1회차_재시도_10초_큐_라우팅() {
        contextRunner.run(ctx -> {
            RetryRoutingResolver resolver = ctx.getBean(RetryRoutingResolver.class);

            String rk = resolver.resolve(RabbitTopologyConfig.RK_ANALYSIS_REQUESTED, 1);

            assertThat(rk).isEqualTo(RabbitTopologyConfig.RETRY_RK_ANALYSIS_10S);
        });
    }

    @Test
    @DisplayName("AnalysisRequested 이벤트: 재시도 2회차는 (설정에 따라) 10분 대기 큐로 라우팅되어야 한다")
    void AnalysisRequested_이벤트_2회차_재시도_10분_큐_라우팅() {
        contextRunner.run(ctx -> {
            RetryRoutingResolver resolver = ctx.getBean(RetryRoutingResolver.class);

            String rk = resolver.resolve(RabbitTopologyConfig.RK_ANALYSIS_REQUESTED, 2);

            assertThat(rk).isEqualTo(RabbitTopologyConfig.RETRY_RK_ANALYSIS_10M);
        });
    }

    @Test
    @DisplayName("재시도 횟수가 0 또는 음수인 경우 1회차와 동일하게 처리해야 한다")
    void 재시도_횟수_0_이하_처리_확인() {
        contextRunner.run(ctx -> {
            RetryRoutingResolver resolver = ctx.getBean(RetryRoutingResolver.class);

            assertThat(resolver.resolve(RabbitTopologyConfig.RK_RECORDED, 0))
                    .isEqualTo(RabbitTopologyConfig.RETRY_RK_RECORDED_10S);

            assertThat(resolver.resolve(RabbitTopologyConfig.RK_ANALYSIS_REQUESTED, -5))
                    .isEqualTo(RabbitTopologyConfig.RETRY_RK_ANALYSIS_10S);
        });
    }

    @Test
    @DisplayName("이벤트 타입에 따라 올바른 Retry Exchange를 반환해야 한다")
    void 이벤트_타입별_Retry_Exchange_반환_확인() {
        contextRunner.run(ctx -> {
            RetryRoutingResolver resolver = ctx.getBean(RetryRoutingResolver.class);

            assertThat(resolver.retryExchange(RabbitTopologyConfig.RK_ANALYSIS_REQUESTED))
                    .isEqualTo(RabbitTopologyConfig.RETRY_EXCHANGE_ANALYSIS);

            assertThat(resolver.retryExchange(RabbitTopologyConfig.RK_RECORDED))
                    .isEqualTo(RabbitTopologyConfig.RETRY_EXCHANGE_RECORDED);
        });
    }

    @Configuration
    @EnableConfigurationProperties(RabbitRetryProperties.class)
    static class TestConfig {
        @Bean
        RetryRoutingResolver retryRoutingResolver(RabbitRetryProperties props) {
            return new RetryRoutingResolver(props);
        }
    }
}
