-- auth_error_cluster
create table if not exists auth_error_cluster (
    id bigserial primary key,
    cluster_key varchar(64) not null,              -- 기본: stack_hash
    status varchar(20) not null default 'OPEN',    -- OPEN/MUTED/RESOLVED (운영 뷰)
    title varchar(200),
    summary text,
    total_count bigint not null default 0,
    first_seen_at timestamptz,
    last_seen_at timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint uk_auth_error_cluster_cluster_key unique (cluster_key),
    constraint ck_auth_error_cluster_total_count check (total_count >= 0)
);

create index if not exists ix_auth_error_cluster_last_seen
    on auth_error_cluster (last_seen_at desc);
