-- auth_error_cluster_item
create table if not exists auth_error_cluster_item (
    cluster_id bigint not null references auth_error_cluster(id) on delete cascade,
    auth_error_id bigint not null references auth_error(id) on delete cascade,
    created_at timestamptz not null default now(),
    constraint pk_auth_error_cluster_item primary key (cluster_id, auth_error_id),
    constraint fk_cluster_item_cluster
        foreign key (cluster_id) references auth_error_cluster(id) on delete cascade
);

create index if not exists ix_cluster_item_auth_error_id
    on auth_error_cluster_item (auth_error_id);
create index ix_cluster_item_created_at
    on auth_error_cluster_item(created_at);