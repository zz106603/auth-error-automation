-- 1) auth_error_event (원천 이벤트)
create table if not exists auth_error_event (
                                                id              bigserial primary key,
                                                request_id      uuid not null,
                                                error_type      varchar(100) not null,
    error_message   text,
    created_at      timestamptz not null default now()
    );

create index if not exists idx_auth_error_event_request_id
    on auth_error_event (request_id);


-- 2) outbox (발행 대기/성공/실패 관리)
create table if not exists outbox_message (
                                              id              bigserial primary key,
                                              aggregate_type  varchar(100) not null,         -- 예: AUTH
    aggregate_id    uuid not null,                 -- 예: request_id
    event_type      varchar(200) not null,         -- 예: AUTH_ERROR_OCCURRED_V1
    payload         jsonb not null,
    status          varchar(30) not null,          -- PENDING, SENT, FAILED ...
    retry_count     int not null default 0,
    next_retry_at   timestamptz,
    created_at      timestamptz not null default now(),
    updated_at      timestamptz not null default now()
    );

create index if not exists idx_outbox_status_next_retry
    on outbox_message (status, next_retry_at);

create index if not exists idx_outbox_aggregate
    on outbox_message (aggregate_type, aggregate_id);
