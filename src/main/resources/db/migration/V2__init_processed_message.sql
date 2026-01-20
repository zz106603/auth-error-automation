-- V2__init_processed_message.sql
-- PostgreSQL

create table if not exists processed_message (
    outbox_id      bigint primary key,

    -- 처리 상태
    status         varchar(20) not null default 'PROCESSING',

    -- lease (동시 처리 방지)
    lease_until    timestamptz,

    -- retry 메타
    retry_count    integer not null default 0,
    next_retry_at  timestamptz,
    last_error     varchar(500),

    -- 완료 시점(DONE에서만 채움)
    processed_at   timestamptz,

    updated_at     timestamptz not null default now()
);

-- PROCESSING lease 스캔 최적화
create index if not exists idx_processed_message_processing_lease
    on processed_message(lease_until)
    where status = 'PROCESSING';

-- RETRY_WAIT + next_retry_at <= now() 조회 최적화
create index if not exists idx_processed_message_retry_ready
    on processed_message(next_retry_at)
    where status = 'RETRY_WAIT';

alter table processed_message
    add column if not exists dead_at timestamptz;

create index if not exists idx_processed_message_dead_at
    on processed_message(dead_at)
    where status = 'DEAD';