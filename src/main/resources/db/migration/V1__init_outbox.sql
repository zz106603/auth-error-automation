-- V1__init_outbox.sql
-- PostgreSQL

create table if not exists outbox_message (
    id              bigserial primary key,

    -- 도메인/이벤트 분류
    aggregate_type  varchar(100) not null,  -- e.g. 'AUTH_ERROR'
    aggregate_id    varchar(100) not null,  -- e.g. requestId or auth_error_event.id
    event_type      varchar(200) not null,  -- e.g. 'AUTH_ERROR_DETECTED_V1'

    -- 이벤트 본문
    payload         jsonb not null,

    -- 중복 방지(멱등성 키)
    idempotency_key varchar(200) not null,

    -- 상태/재시도
    status          varchar(30) not null default 'PENDING' check (status in ('PENDING', 'PUBLISHED', 'FAILED', 'DEAD')),

    retry_count     int not null default 0,
    max_retries     int not null default 10,
    next_retry_at   timestamptz not null default now(),

    last_error      text,

    -- 타임스탬프 (updated_at은 JPA Auditing/PreUpdate로 관리 권장)
    created_at      timestamptz not null default now(),
    updated_at      timestamptz not null default now(),
    published_at    timestamptz
    );

-- 같은 사건(멱등성 키) 중복 저장 방지
create unique index if not exists ux_outbox_idempotency_key
    on outbox_message (idempotency_key);

-- Poller가 빠르게 가져오도록 (핵심 인덱스)
create index if not exists ix_outbox_polling
    on outbox_message (status, next_retry_at, id);

-- (옵션) 특정 aggregate의 이벤트 조회/추적용
create index if not exists ix_outbox_aggregate
    on outbox_message (aggregate_type, aggregate_id);
