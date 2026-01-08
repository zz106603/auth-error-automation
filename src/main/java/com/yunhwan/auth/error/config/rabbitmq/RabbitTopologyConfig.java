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

    // RETRY (10s 예시)
    public static final String RETRY_EXCHANGE = "auth.error.retry.exchange";
    public static final String RETRY_QUEUE_10S = "auth.error.queue.retry.10s";
    public static final String RETRY_ROUTING_KEY_10S = "auth.error.retry.10s";

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

    @Bean
    public Queue authErrorRetryQueue10s() {
        // TTL 지나면 Main Exchange로 돌아오게 설정
        return QueueBuilder.durable(RETRY_QUEUE_10S)
                .withArgument("x-message-ttl", 10_000)                 // 10s
                .withArgument("x-dead-letter-exchange", EXCHANGE)      // 다시 메인으로
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
        return BindingBuilder.bind(authErrorRetryQueue10s).to(authErrorRetryExchange).with(RETRY_ROUTING_KEY_10S);
    }
}
