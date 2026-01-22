package com.yunhwan.auth.error.infra.messaging.rabbit;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitTopologyConfig {

    // =========================
    // Main exchange (shared)
    // =========================
    public static final String EXCHANGE = "auth.error.exchange";

    // =========================
    // Event routing keys
    // =========================
    public static final String RK_RECORDED = "auth.error.recorded.v1";
    public static final String RK_ANALYSIS_REQUESTED = "auth.error.analysis.requested.v1";

    // =========================
    // [RECORDED] main queue
    // =========================
    public static final String Q_RECORDED = "auth.error.recorded.q";

    // [RECORDED] DLQ/DLX
    public static final String DLX_RECORDED = "auth.error.recorded.dlx";
    public static final String DLQ_RECORDED = "auth.error.recorded.q.dlq";
    public static final String DLQ_RK_RECORDED = "auth.error.recorded.v1.dlq";

    // [RECORDED] Retry exchange + routing keys
    public static final String RETRY_EXCHANGE_RECORDED = "auth.error.recorded.retry.exchange";
    public static final String RETRY_RK_RECORDED_10S = "auth.error.recorded.retry.10s";
    public static final String RETRY_RK_RECORDED_1M  = "auth.error.recorded.retry.1m";
    public static final String RETRY_RK_RECORDED_10M = "auth.error.recorded.retry.10m";

    // [RECORDED] Retry queues
    public static final String RETRY_Q_RECORDED_10S = "auth.error.recorded.retry.q.10s";
    public static final String RETRY_Q_RECORDED_1M  = "auth.error.recorded.retry.q.1m";
    public static final String RETRY_Q_RECORDED_10M = "auth.error.recorded.retry.q.10m";

    // =========================
    // [ANALYSIS] main queue
    // =========================
    public static final String Q_ANALYSIS = "auth.error.analysis.q";

    // [ANALYSIS] DLQ/DLX
    public static final String DLX_ANALYSIS = "auth.error.analysis.dlx";
    public static final String DLQ_ANALYSIS = "auth.error.analysis.q.dlq";
    public static final String DLQ_RK_ANALYSIS = "auth.error.analysis.requested.v1.dlq";

    // [ANALYSIS] Retry exchange + routing keys
    public static final String RETRY_EXCHANGE_ANALYSIS = "auth.error.analysis.retry.exchange";
    public static final String RETRY_RK_ANALYSIS_10S = "auth.error.analysis.retry.10s";
    public static final String RETRY_RK_ANALYSIS_1M  = "auth.error.analysis.retry.1m";
    public static final String RETRY_RK_ANALYSIS_10M = "auth.error.analysis.retry.10m";

    // [ANALYSIS] Retry queues
    public static final String RETRY_Q_ANALYSIS_10S = "auth.error.analysis.retry.q.10s";
    public static final String RETRY_Q_ANALYSIS_1M  = "auth.error.analysis.retry.q.1m";
    public static final String RETRY_Q_ANALYSIS_10M = "auth.error.analysis.retry.q.10m";

    private final RabbitRetryProperties retryProps;

    public RabbitTopologyConfig(RabbitRetryProperties retryProps) {
        this.retryProps = retryProps;
    }

    // ===== Exchanges =====
    @Bean
    public TopicExchange authErrorExchange() {
        return new TopicExchange(EXCHANGE, true, false);
    }

    @Bean
    public TopicExchange recordedDlx() {
        return new TopicExchange(DLX_RECORDED, true, false);
    }

    @Bean
    public TopicExchange analysisDlx() {
        return new TopicExchange(DLX_ANALYSIS, true, false);
    }

    @Bean
    public TopicExchange recordedRetryExchange() {
        return new TopicExchange(RETRY_EXCHANGE_RECORDED, true, false);
    }

    @Bean
    public TopicExchange analysisRetryExchange() {
        return new TopicExchange(RETRY_EXCHANGE_ANALYSIS, true, false);
    }

    // ===== Main Queues =====
    @Bean
    public Queue authErrorRecordedQueue() {
        return QueueBuilder.durable(Q_RECORDED)
                .withArgument("x-dead-letter-exchange", DLX_RECORDED)
                .withArgument("x-dead-letter-routing-key", DLQ_RK_RECORDED)
                .build();
    }

    @Bean
    public Queue authErrorAnalysisQueue() {
        return QueueBuilder.durable(Q_ANALYSIS)
                .withArgument("x-dead-letter-exchange", DLX_ANALYSIS)
                .withArgument("x-dead-letter-routing-key", DLQ_RK_ANALYSIS)
                .build();
    }

    // ===== DLQs =====
    @Bean
    public Queue authErrorRecordedDlq() {
        return QueueBuilder.durable(DLQ_RECORDED).build();
    }

    @Bean
    public Queue authErrorAnalysisDlq() {
        return QueueBuilder.durable(DLQ_ANALYSIS).build();
    }

    // ===== Retry Queues (RECORDED) =====
    @Bean
    public Queue recordedRetryQueue10s() {
        return QueueBuilder.durable(RETRY_Q_RECORDED_10S)
                .withArgument("x-message-ttl", retryProps.getTtl10s())
                .withArgument("x-dead-letter-exchange", EXCHANGE)
                .withArgument("x-dead-letter-routing-key", RK_RECORDED)
                .build();
    }

    @Bean
    public Queue recordedRetryQueue1m() {
        return QueueBuilder.durable(RETRY_Q_RECORDED_1M)
                .withArgument("x-message-ttl", retryProps.getTtl1m())
                .withArgument("x-dead-letter-exchange", EXCHANGE)
                .withArgument("x-dead-letter-routing-key", RK_RECORDED)
                .build();
    }

    @Bean
    public Queue recordedRetryQueue10m() {
        return QueueBuilder.durable(RETRY_Q_RECORDED_10M)
                .withArgument("x-message-ttl", retryProps.getTtl10m())
                .withArgument("x-dead-letter-exchange", EXCHANGE)
                .withArgument("x-dead-letter-routing-key", RK_RECORDED)
                .build();
    }

    // ===== Retry Queues (ANALYSIS) =====
    @Bean
    public Queue analysisRetryQueue10s() {
        return QueueBuilder.durable(RETRY_Q_ANALYSIS_10S)
                .withArgument("x-message-ttl", retryProps.getTtl10s())
                .withArgument("x-dead-letter-exchange", EXCHANGE)
                .withArgument("x-dead-letter-routing-key", RK_ANALYSIS_REQUESTED)
                .build();
    }

    @Bean
    public Queue analysisRetryQueue1m() {
        return QueueBuilder.durable(RETRY_Q_ANALYSIS_1M)
                .withArgument("x-message-ttl", retryProps.getTtl1m())
                .withArgument("x-dead-letter-exchange", EXCHANGE)
                .withArgument("x-dead-letter-routing-key", RK_ANALYSIS_REQUESTED)
                .build();
    }

    @Bean
    public Queue analysisRetryQueue10m() {
        return QueueBuilder.durable(RETRY_Q_ANALYSIS_10M)
                .withArgument("x-message-ttl", retryProps.getTtl10m())
                .withArgument("x-dead-letter-exchange", EXCHANGE)
                .withArgument("x-dead-letter-routing-key", RK_ANALYSIS_REQUESTED)
                .build();
    }

    // ===== Bindings: Main =====
    @Bean
    public Binding recordedBinding(TopicExchange authErrorExchange, Queue authErrorRecordedQueue) {
        return BindingBuilder.bind(authErrorRecordedQueue).to(authErrorExchange).with(RK_RECORDED);
    }

    @Bean
    public Binding analysisRequestedBinding(TopicExchange authErrorExchange, Queue authErrorAnalysisQueue) {
        return BindingBuilder.bind(authErrorAnalysisQueue).to(authErrorExchange).with(RK_ANALYSIS_REQUESTED);
    }

    // ===== Bindings: DLQ =====
    @Bean
    public Binding recordedDlqBinding(TopicExchange recordedDlx, Queue authErrorRecordedDlq) {
        return BindingBuilder.bind(authErrorRecordedDlq).to(recordedDlx).with(DLQ_RK_RECORDED);
    }

    @Bean
    public Binding analysisDlqBinding(TopicExchange analysisDlx, Queue authErrorAnalysisDlq) {
        return BindingBuilder.bind(authErrorAnalysisDlq).to(analysisDlx).with(DLQ_RK_ANALYSIS);
    }

    // ===== Bindings: Retry (RECORDED) =====
    @Bean
    public Binding recordedRetry10sBinding(TopicExchange recordedRetryExchange, Queue recordedRetryQueue10s) {
        return BindingBuilder.bind(recordedRetryQueue10s).to(recordedRetryExchange).with(RETRY_RK_RECORDED_10S);
    }

    @Bean
    public Binding recordedRetry1mBinding(TopicExchange recordedRetryExchange, Queue recordedRetryQueue1m) {
        return BindingBuilder.bind(recordedRetryQueue1m).to(recordedRetryExchange).with(RETRY_RK_RECORDED_1M);
    }

    @Bean
    public Binding recordedRetry10mBinding(TopicExchange recordedRetryExchange, Queue recordedRetryQueue10m) {
        return BindingBuilder.bind(recordedRetryQueue10m).to(recordedRetryExchange).with(RETRY_RK_RECORDED_10M);
    }

    // ===== Bindings: Retry (ANALYSIS) =====
    @Bean
    public Binding analysisRetry10sBinding(TopicExchange analysisRetryExchange, Queue analysisRetryQueue10s) {
        return BindingBuilder.bind(analysisRetryQueue10s).to(analysisRetryExchange).with(RETRY_RK_ANALYSIS_10S);
    }

    @Bean
    public Binding analysisRetry1mBinding(TopicExchange analysisRetryExchange, Queue analysisRetryQueue1m) {
        return BindingBuilder.bind(analysisRetryQueue1m).to(analysisRetryExchange).with(RETRY_RK_ANALYSIS_1M);
    }

    @Bean
    public Binding analysisRetry10mBinding(TopicExchange analysisRetryExchange, Queue analysisRetryQueue10m) {
        return BindingBuilder.bind(analysisRetryQueue10m).to(analysisRetryExchange).with(RETRY_RK_ANALYSIS_10M);
    }
}
