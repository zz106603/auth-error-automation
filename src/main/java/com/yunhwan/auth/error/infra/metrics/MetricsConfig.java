package com.yunhwan.auth.error.infra.metrics;

public final class MetricsConfig {

    private MetricsConfig() {}

    // 체크리스트(throughput/E2E/Backlog/DLQ) 충족용 지표
    public static final String METRIC_INGEST = "auth_error.ingest";
    public static final String METRIC_PUBLISH = "auth_error.publish";
    public static final String METRIC_CONSUME = "auth_error.consume";
    public static final String METRIC_RETRY_ENQUEUE = "auth_error.retry.enqueue";
    public static final String METRIC_DLQ = "auth_error.dlq";
    public static final String METRIC_E2E = "auth_error.e2e";
    public static final String METRIC_OUTBOX_AGE = "auth_error.outbox.age";
    public static final String METRIC_OUTBOX_AGE_P95 = "auth_error.outbox.age.p95";
    public static final String METRIC_OUTBOX_AGE_P99 = "auth_error.outbox.age.p99";
    public static final String METRIC_OUTBOX_AGE_SLOPE = "auth_error.outbox.age.slope_ms_per_10s";
    public static final String METRIC_PUBLISH_LAST_SUCCESS_EPOCH_MS = "auth_error.publish.last_success_epoch_ms";

    // STOP 5.5(MQ Health) 계산용
    public static final String METRIC_RABBIT_READY = "auth_error.rabbit.ready";
    public static final String METRIC_RABBIT_UNACKED = "auth_error.rabbit.unacked";
    public static final String METRIC_RABBIT_PUBLISH_RATE = "auth_error.rabbit.publish_rate";
    public static final String METRIC_RABBIT_DELIVER_RATE = "auth_error.rabbit.deliver_rate";
    public static final String METRIC_RABBIT_RETRY_DEPTH = "auth_error.rabbit.retry_depth";
    public static final String METRIC_RABBIT_DLQ_DEPTH = "auth_error.rabbit.dlq_depth";

    // 고카디널리티 금지(요청ID/에러메시지/스택 제외)
    public static final String TAG_EVENT_TYPE = "event_type";
    public static final String TAG_QUEUE = "queue";
    public static final String TAG_RESULT = "result";
    public static final String TAG_RETRY_BUCKET = "retry_bucket";
    public static final String TAG_REASON = "reason";
    public static final String TAG_QUEUE_TYPE = "queue_type";
    public static final String TAG_VHOST = "vhost";

    // 고정 결과값(집계 안정성)
    public static final String RESULT_SUCCESS = "success";
    public static final String RESULT_NACK = "nack";
    public static final String RESULT_RETURNED = "returned";
    public static final String RESULT_TIMEOUT = "timeout";
    public static final String RESULT_ERROR = "error";
    public static final String RESULT_RETRY = "retry";
    public static final String RESULT_DEAD = "dead";
    public static final String RESULT_REJECT = "reject";

    // 재시도 분포(1/2/3+)만 집계
    public static final String RETRY_BUCKET_1 = "1";
    public static final String RETRY_BUCKET_2 = "2";
    public static final String RETRY_BUCKET_3PLUS = "3plus";

    // 사유 택소노미(안정적 값만)
    public static final String REASON_RETRYABLE = "retryable";
    public static final String REASON_NON_RETRYABLE = "non_retryable";
    public static final String REASON_MISSING_OUTBOX_ID = "missing_outbox_id";
    public static final String REASON_MISSING_HEADERS = "missing_headers";
    public static final String REASON_INVALID_PAYLOAD = "invalid_payload";
    public static final String REASON_MAX_RETRIES = "max_retries";
    public static final String REASON_DLQ_ARRIVED = "dlq_arrived";
    public static final String REASON_UNKNOWN = "unknown";
}
