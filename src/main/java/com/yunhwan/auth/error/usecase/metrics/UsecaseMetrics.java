package com.yunhwan.auth.error.usecase.metrics;

public final class UsecaseMetrics {

    private UsecaseMetrics() {
    }

    public static final String METRIC_INGEST_TRANSACTION = "auth_error_ingest_transaction";
    public static final String METRIC_OUTBOX_POLLER_CLAIM = "auth_error_outbox_poller_claim";
    public static final String METRIC_OUTBOX_PUBLISH = "auth_error_outbox_publish";
    public static final String METRIC_RECORDED_HANDLER_PAYLOAD_PARSE = "auth_error.recorded.handler.payload_parse";
    public static final String METRIC_RECORDED_HANDLER_AUTH_ERROR_LOOKUP = "auth_error.recorded.handler.auth_error_lookup";
    public static final String METRIC_RECORDED_HANDLER_IDEMPOTENCY_GUARD = "auth_error.recorded.handler.idempotency_guard";
    public static final String METRIC_RECORDED_HANDLER_OUTBOX_ENQUEUE_TOTAL = "auth_error.recorded.handler.outbox_enqueue";

    public static final String TAG_EVENT_TYPE = "event_type";
}
