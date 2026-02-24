package com.yunhwan.auth.error.infra.metrics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import static com.yunhwan.auth.error.infra.metrics.MetricsConfig.*;

@Slf4j
@Component
@Profile("!test")
@ConditionalOnProperty(name = "metrics.rabbit.enabled", havingValue = "true", matchIfMissing = false)
public class RabbitMqMetricsPoller implements SchedulingConfigurer {

    private final RabbitManagementProperties props;
    private final MeterRegistry meterRegistry;
    private final TaskScheduler outboxTaskScheduler;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    public RabbitMqMetricsPoller(
            RabbitManagementProperties props,
            MeterRegistry meterRegistry,
            TaskScheduler outboxTaskScheduler,
            ObjectMapper objectMapper,
            @Qualifier("rabbitMqMetricsRestTemplate") RestTemplate restTemplate
    ) {
        this.props = props;
        this.meterRegistry = meterRegistry;
        this.outboxTaskScheduler = outboxTaskScheduler;
        this.objectMapper = objectMapper;
        this.restTemplate = restTemplate;
    }

    // 값만 갱신(태그 고정, 고카디널리티 없음)
    private final Map<String, AtomicReference<Double>> gauges = new ConcurrentHashMap<>();

    @PostConstruct
    void init() {
        // no-op; gauges are created lazily on first poll
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar taskRegistrar) {
        taskRegistrar.setScheduler(outboxTaskScheduler);
        // STOP 5.5 평가용 주기
        taskRegistrar.addFixedDelayTask(this::tick, 10_000L);
    }

    void tick() {
        if (!StringUtils.hasText(props.getBaseUrl())) {
            return;
        }
        try {
            String vhost = props.getVhost();
            String url = props.getBaseUrl() + "/api/queues/" + vhost;

            HttpHeaders headers = new HttpHeaders();
            headers.add("Authorization", basicAuth(props.getUsername(), props.getPassword()));
            ResponseEntity<String> res = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), String.class);
            if (!res.getStatusCode().is2xxSuccessful() || res.getBody() == null) {
                return;
            }

            JsonNode root = objectMapper.readTree(res.getBody());
            if (!root.isArray()) return;

            long retryDepth = 0;
            long dlqDepth = 0;

            for (JsonNode q : root) {
                String name = q.path("name").asText();
                long ready = q.path("messages_ready").asLong(0);
                long unacked = q.path("messages_unacknowledged").asLong(0);
                double publishRate = q.path("message_stats").path("publish_details").path("rate").asDouble(0.0);
                double deliverRate = q.path("message_stats").path("deliver_details").path("rate").asDouble(0.0);

                // Ready/Unacked/Rate를 큐별로 기록 (저카디널리티)
                recordGauge(METRIC_RABBIT_READY, ready, name, vhost);
                recordGauge(METRIC_RABBIT_UNACKED, unacked, name, vhost);
                recordGauge(METRIC_RABBIT_PUBLISH_RATE, publishRate, name, vhost);
                recordGauge(METRIC_RABBIT_DELIVER_RATE, deliverRate, name, vhost);

                if (isRetryQueue(name)) {
                    retryDepth += ready + unacked;
                }
                if (isDlqQueue(name)) {
                    dlqDepth += ready + unacked;
                }
            }

            // retry/dlq depth 합산 지표
            recordGauge(METRIC_RABBIT_RETRY_DEPTH, retryDepth, "all", vhost);
            recordGauge(METRIC_RABBIT_DLQ_DEPTH, dlqDepth, "all", vhost);
        } catch (Exception e) {
            log.warn("[RabbitMqMetrics] poll failed: {}", e.toString());
        }
    }

    private void recordGauge(String metric, double value, String queue, String vhost) {
        String key = metric + "|" + queue + "|" + vhost;
        AtomicReference<Double> holder = gauges.computeIfAbsent(key, k -> {
            AtomicReference<Double> v = new AtomicReference<>(0.0);
            Gauge.builder(metric, v, ref -> ref.get())
                    .tag(TAG_QUEUE, queue)
                    .tag(TAG_VHOST, vhost)
                    .register(meterRegistry);
            return v;
        });
        holder.set(value);
    }

    private String basicAuth(String user, String pass) {
        String raw = user + ":" + pass;
        return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private boolean isRetryQueue(String name) {
        // retry depth 계산 대상
        return name.contains(".retry.");
    }

    private boolean isDlqQueue(String name) {
        // DLQ depth 계산 대상
        return name.endsWith(".dlq");
    }
}
