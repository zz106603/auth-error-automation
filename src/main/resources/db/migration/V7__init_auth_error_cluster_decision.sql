-- 1) cluster decision history
create table if not exists auth_error_cluster_decision (
    id bigserial primary key,
    cluster_id bigint not null references auth_error_cluster(id) on delete cascade,
    idempotency_key varchar(100) not null,
    decision_type varchar(30) not null,
    note text,
    decided_by varchar(30) not null,
    status varchar(20) not null default 'APPLIED', -- APPLIED/PARTIAL/FAILED
    total_targets int not null default 0,
    applied_count int not null default 0,
    skipped_count int not null default 0,
    failed_count int not null default 0,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint uq_cluster_decision_idem unique (idempotency_key)
);

create index if not exists ix_cluster_decision_cluster_id_created_at
    on auth_error_cluster_decision (cluster_id, created_at desc);
