-- V12__add_auth_error_diagnostic_views.sql
-- Read-only diagnostic views for MCP/statistics queries.

create or replace view auth_error_hourly_type_stats as
select
    date_trunc('hour', occurred_at) as bucket_hour,
    error_type,
    auth_failure_severity,
    auth_failure_retryable,
    auth_failure_security_signal,
    count(*) as error_count,
    min(occurred_at) as first_seen_at,
    max(occurred_at) as last_seen_at
from auth_error
group by
    date_trunc('hour', occurred_at),
    error_type,
    auth_failure_severity,
    auth_failure_retryable,
    auth_failure_security_signal;

create or replace view auth_error_context_distribution as
select
    date_trunc('hour', occurred_at) as bucket_hour,
    error_type,
    provider,
    client_type,
    http_status,
    endpoint,
    count(*) as error_count,
    min(occurred_at) as first_seen_at,
    max(occurred_at) as last_seen_at
from auth_error
group by
    date_trunc('hour', occurred_at),
    error_type,
    provider,
    client_type,
    http_status,
    endpoint;

create or replace view auth_error_cluster_summary as
select
    error_type,
    provider,
    stack_hash,
    auth_failure_severity,
    count(*) as error_count,
    count(distinct principal_hash) filter (where principal_hash is not null) as principal_hash_count,
    count(distinct ip_hash) filter (where ip_hash is not null) as ip_hash_count,
    min(occurred_at) as first_seen_at,
    max(occurred_at) as last_seen_at
from auth_error
group by
    error_type,
    provider,
    stack_hash,
    auth_failure_severity;

create or replace view retry_publish_request_summary as
select
    date_trunc('hour', created_at) as bucket_hour,
    event_type,
    status,
    count(*) as request_count,
    sum(publish_retry_count) as publish_retry_count_sum,
    max(publish_retry_count) as publish_retry_count_max,
    min(next_publish_at) as earliest_next_publish_at,
    max(updated_at) as last_updated_at
from retry_publish_request
group by
    date_trunc('hour', created_at),
    event_type,
    status;

create or replace view dead_letter_reason_summary as
select
    date_trunc('hour', last_seen_at) as bucket_hour,
    reason_code,
    replay_status,
    dlq_queue,
    event_type,
    count(*) as message_count,
    sum(delivery_count) as delivery_count_sum,
    max(retry_count) as retry_count_max,
    min(first_seen_at) as first_seen_at,
    max(last_seen_at) as last_seen_at
from dead_letter_message
group by
    date_trunc('hour', last_seen_at),
    reason_code,
    replay_status,
    dlq_queue,
    event_type;
