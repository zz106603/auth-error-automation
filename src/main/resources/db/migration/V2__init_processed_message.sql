-- V2__init_processed_message.sql
-- PostgreSQL

create table if not exists processed_message (
    outbox_id     bigint primary key,
    processed_at  timestamptz not null default now()
);

-- 조회 성능이 필요하면(나중에) processed_at 인덱스 정도만 추가
-- create index if not exists idx_processed_message_processed_at on processed_message(processed_at);
