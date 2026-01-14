package com.yunhwan.auth.error.config.rabbitmq;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitTopologyConfig {

    public static final String EXCHANGE = "auth.error.exchange";
    public static final String QUEUE = "auth.error.queue";
    public static final String ROUTING_KEY = "auth.error.detected.v1";

    // DLQ
    public static final String DLX = "auth.error.dlx";
    public static final String DLQ = "auth.error.queue.dlq";
    public static final String DLQ_ROUTING_KEY = "auth.error.detected.v1.dlq";

    // RETRY EXCHANGE
    public static final String RETRY_EXCHANGE = "auth.error.retry.exchange";

    // === Retry routing keys (ladder) ===
    public static final String RETRY_RK_10S = "auth.error.retry.10s";
    public static final String RETRY_RK_1M  = "auth.error.retry.1m";
    public static final String RETRY_RK_10M = "auth.error.retry.10m";

    // === Retry queues ===
    public static final String RETRY_Q_10S = "auth.error.retry.q.10s";
    public static final String RETRY_Q_1M  = "auth.error.retry.q.1m";
    public static final String RETRY_Q_10M = "auth.error.retry.q.10m";

    // TTL(ms)
    public static final int RETRY_TTL_10S = 10_000;
    public static final int RETRY_TTL_1M  = 60_000;
    public static final int RETRY_TTL_10M = 600_000;

    @Bean
    public TopicExchange authErrorExchange() {
        return new TopicExchange(EXCHANGE, true, false);
    }

    @Bean
    public TopicExchange authErrorDlx() {
        return new TopicExchange(DLX, true, false);
    }

    @Bean
    public TopicExchange authErrorRetryExchange() {
        return new TopicExchange(RETRY_EXCHANGE, true, false);
    }

    @Bean
    public Queue authErrorQueue() {
        return QueueBuilder.durable(QUEUE)
                .withArgument("x-dead-letter-exchange", DLX)
                .withArgument("x-dead-letter-routing-key", DLQ_ROUTING_KEY)
                .build();
    }

    @Bean
    public Queue authErrorDlq() {
        return QueueBuilder.durable(DLQ).build();
    }

    /** 10s retry queue */
    @Bean
    public Queue authErrorRetryQueue10s() {
        return QueueBuilder.durable(RETRY_Q_10S)
                .withArgument("x-message-ttl", RETRY_TTL_10S)
                .withArgument("x-dead-letter-exchange", EXCHANGE)
                .withArgument("x-dead-letter-routing-key", ROUTING_KEY)
                .build();
    }

    /** 1m retry queue */
    @Bean
    public Queue authErrorRetryQueue1m() {
        return QueueBuilder.durable(RETRY_Q_1M)
                .withArgument("x-message-ttl", RETRY_TTL_1M)
                .withArgument("x-dead-letter-exchange", EXCHANGE)
                .withArgument("x-dead-letter-routing-key", ROUTING_KEY)
                .build();
    }

    /** 10m retry queue */
    @Bean
    public Queue authErrorRetryQueue10m() {
        return QueueBuilder.durable(RETRY_Q_10M)
                .withArgument("x-message-ttl", RETRY_TTL_10M)
                .withArgument("x-dead-letter-exchange", EXCHANGE)
                .withArgument("x-dead-letter-routing-key", ROUTING_KEY)
                .build();
    }

    @Bean
    public Binding authErrorBinding(TopicExchange authErrorExchange, Queue authErrorQueue) {
        return BindingBuilder.bind(authErrorQueue).to(authErrorExchange).with(ROUTING_KEY);
    }

    @Bean
    public Binding authErrorDlqBinding(TopicExchange authErrorDlx, Queue authErrorDlq) {
        return BindingBuilder.bind(authErrorDlq).to(authErrorDlx).with(DLQ_ROUTING_KEY);
    }

    @Bean
    public Binding authErrorRetry10sBinding(TopicExchange authErrorRetryExchange, Queue authErrorRetryQueue10s) {
        return BindingBuilder.bind(authErrorRetryQueue10s).to(authErrorRetryExchange).with(RETRY_RK_10S);
    }

    @Bean
    public Binding authErrorRetry1mBinding(TopicExchange authErrorRetryExchange, Queue authErrorRetryQueue1m) {
        return BindingBuilder.bind(authErrorRetryQueue1m).to(authErrorRetryExchange).with(RETRY_RK_1M);
    }

    @Bean
    public Binding authErrorRetry10mBinding(TopicExchange authErrorRetryExchange, Queue authErrorRetryQueue10m) {
        return BindingBuilder.bind(authErrorRetryQueue10m).to(authErrorRetryExchange).with(RETRY_RK_10M);
    }
}
