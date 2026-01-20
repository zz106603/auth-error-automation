package com.yunhwan.auth.error.infra.messaging.rabbit;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class RetryRoutingResolverTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withUserConfiguration(TestConfig.class)
                    .withPropertyValues(
                            "auth-error.rabbit.retry.fast-max=1",
                            "auth-error.rabbit.retry.medium-max=1"
                    );

    @Test
    void nextRetry_1_routes_to_10s() {
        contextRunner.run(ctx -> {
            RetryRoutingResolver resolver = ctx.getBean(RetryRoutingResolver.class);
            assertThat(resolver.resolve(1)).isEqualTo(RabbitTopologyConfig.RETRY_RK_10S);
        });
    }

    @Test
    void nextRetry_2_routes_to_10m() {
        contextRunner.run(ctx -> {
            RetryRoutingResolver resolver = ctx.getBean(RetryRoutingResolver.class);
            assertThat(resolver.resolve(2)).isEqualTo(RabbitTopologyConfig.RETRY_RK_10M);
        });
    }

    @Test
    void zero_or_negative_treated_as_first_retry() {
        contextRunner.run(ctx -> {
            RetryRoutingResolver resolver = ctx.getBean(RetryRoutingResolver.class);
            assertThat(resolver.resolve(0)).isEqualTo(RabbitTopologyConfig.RETRY_RK_10S);
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
