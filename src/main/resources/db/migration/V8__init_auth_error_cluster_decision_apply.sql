-- 2) apply log (decisionId, authErrorId) unique
create table if not exists auth_error_cluster_decision_apply (
    id bigserial primary key,
    decision_id bigint not null references auth_error_cluster_decision(id) on delete cascade,
    auth_error_id bigint not null references auth_error(id) on delete cascade,
    outcome varchar(20) not null, -- APPLIED/SKIPPED/FAILED
    message text,
    created_at timestamptz not null default now(),
    constraint uq_cluster_decision_apply unique (decision_id, auth_error_id)
);

create index if not exists ix_cluster_decision_apply_decision_id
    on auth_error_cluster_decision_apply (decision_id);
