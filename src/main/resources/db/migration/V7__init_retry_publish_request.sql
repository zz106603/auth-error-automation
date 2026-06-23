create table if not exists retry_publish_request (
    id                  bigserial primary key,
    source_outbox_id    bigint not null,
    event_type          varchar(200) not null,
    aggregate_type      varchar(100) not null,
    payload             jsonb not null,
    retry_count         int not null,
    next_retry_at       timestamptz not null,
    last_error          text,
    status              varchar(30) not null default 'PENDING'
        check (status in ('PENDING', 'PROCESSING', 'PUBLISHED', 'DEAD')),
    publish_retry_count int not null default 0,
    processing_owner    varchar(100),
    processing_started_at timestamptz,
    published_at        timestamptz,
    next_publish_at     timestamptz,
    last_publish_error  text,
    created_at          timestamptz not null default now(),
    updated_at          timestamptz not null default now()
);

create unique index if not exists ux_retry_publish_request_outbox_retry
    on retry_publish_request (source_outbox_id, retry_count);

create index if not exists ix_retry_publish_request_claim
    on retry_publish_request (status, next_publish_at, created_at);
