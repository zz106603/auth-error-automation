-- V2__init_processed_message.sql
-- PostgreSQL

create table if not exists processed_message (
    outbox_id     bigint primary key,
    processed_at  timestamptz not null default now()
);
