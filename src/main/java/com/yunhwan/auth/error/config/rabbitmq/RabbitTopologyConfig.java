package com.yunhwan.auth.error.config.rabbitmq;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitTopologyConfig {

    public static final String EXCHANGE = "auth.error.exchange";
    public static final String QUEUE = "auth.error.queue";
    public static final String ROUTING_KEY = "auth.error.detected.v1";

    @Bean
    public TopicExchange authErrorExchange() {
        return new TopicExchange(EXCHANGE, true, false);
    }

    @Bean
    public Queue authErrorQueue() {
        return QueueBuilder.durable(QUEUE).build();
    }

    @Bean
    public Binding authErrorBinding(TopicExchange authErrorExchange, Queue authErrorQueue) {
        return BindingBuilder.bind(authErrorQueue).to(authErrorExchange).with(ROUTING_KEY);
    }
}
