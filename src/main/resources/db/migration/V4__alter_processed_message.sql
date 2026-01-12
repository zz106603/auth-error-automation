-- V4__alter_processed_message.sql
-- PostgreSQL

-- 1) status + lease 관련 컬럼 추가
alter table processed_message
    add column if not exists status varchar(20) not null default 'PROCESSING',
    add column if not exists lease_until timestamptz,
    add column if not exists updated_at timestamptz not null default now();

-- 2) processed_at은 DONE 시점에만 채우도록 nullable 허용(권장)
alter table processed_message
    alter column processed_at drop not null;

-- 3) outbox_id는 PK(이미 그렇게 쓰고 있다 가정)
-- 4) 성능용 인덱스(선점/만료 스캔)
create index if not exists idx_processed_message_status_lease
    on processed_message(status, lease_until);
