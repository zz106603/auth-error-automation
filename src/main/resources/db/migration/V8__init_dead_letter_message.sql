create table if not exists dead_letter_message (
    id                              bigserial primary key,
    dedupe_key                      varchar(128) not null,
    dlq_queue                       varchar(200) not null,
    source_queue                    varchar(200),
    source_exchange                 varchar(200),
    source_routing_key              varchar(200),
    dead_letter_exchange            varchar(200),
    dead_letter_routing_key         varchar(200),
    outbox_id                       bigint,
    event_type                      varchar(200),
    aggregate_type                  varchar(100),
    payload                         text not null,
    payload_hash                    varchar(64) not null,
    payload_size_bytes              integer not null,
    reason_code                     varchar(80) not null,
    broker_death_reason             varchar(80),
    x_death                         jsonb,
    retry_count                     integer,
    processed_message_status_at_arrival varchar(30),
    outbox_status_at_arrival        varchar(30),
    first_seen_at                   timestamptz not null default now(),
    last_seen_at                    timestamptz not null default now(),
    delivery_count                  integer not null default 1,
    replay_status                   varchar(30) not null,
    replay_requested_at             timestamptz,
    replay_started_at               timestamptz,
    replayed_at                     timestamptz,
    replay_failed_at                timestamptz,
    replay_failure_reason           text,
    operator_note                   text,
    created_at                      timestamptz not null default now(),
    updated_at                      timestamptz not null default now(),
    constraint ux_dead_letter_message_dedupe_key unique (dedupe_key),
    constraint ck_dead_letter_message_reason_code check (
        reason_code in (
            'CONTRACT_MISSING_OUTBOX_ID',
            'CONTRACT_MISSING_EVENT_TYPE',
            'CONTRACT_MISSING_AGGREGATE_TYPE',
            'CONTRACT_MISSING_HEADERS',
            'PAYLOAD_INVALID_JSON',
            'PAYLOAD_MISSING_AUTH_ERROR_ID',
            'DOMAIN_AUTH_ERROR_NOT_FOUND',
            'HANDLER_NON_RETRYABLE',
            'RETRY_EXHAUSTED',
            'CONSUMER_PROCESSING_FAILED',
            'BROKER_REJECTED',
            'BROKER_EXPIRED',
            'BROKER_MAXLEN',
            'UNKNOWN'
        )
    ),
    constraint ck_dead_letter_message_replay_status check (
        replay_status in (
            'NOT_REPLAYABLE',
            'REPLAYABLE',
            'REPLAY_REQUESTED',
            'REPLAYING',
            'REPLAYED',
            'REPLAY_FAILED',
            'DISCARDED',
            'BLOCKED'
        )
    )
);

create index if not exists idx_dead_letter_message_outbox_id
    on dead_letter_message(outbox_id)
    where outbox_id is not null;

create index if not exists idx_dead_letter_message_reason_code
    on dead_letter_message(reason_code);

create index if not exists idx_dead_letter_message_replay_status
    on dead_letter_message(replay_status);

create index if not exists idx_dead_letter_message_last_seen_at
    on dead_letter_message(last_seen_at);

create index if not exists idx_dead_letter_message_dlq_queue_reason
    on dead_letter_message(dlq_queue, reason_code);
