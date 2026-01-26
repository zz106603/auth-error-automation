-- V4___auth_error_analysis_result.sql

create table if not exists auth_error_analysis_result (
    id bigserial primary key,

    auth_error_id bigint not null,
    analysis_version varchar(30) not null,
    model varchar(100) not null,

    category varchar(50),
    severity varchar(10),
    summary text,
    suggested_action text,
    confidence numeric(4,3),

    created_at timestamptz not null default now(),

    constraint fk_auth_error_analysis_result_auth_error
      foreign key (auth_error_id) references auth_error(id)
);

create index if not exists ix_auth_error_analysis_result_auth_error_id
    on auth_error_analysis_result (auth_error_id);

create index if not exists ix_auth_error_analysis_result_created_at
    on auth_error_analysis_result (created_at);
