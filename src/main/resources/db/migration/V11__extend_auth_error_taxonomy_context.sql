-- V11__extend_auth_error_taxonomy_context.sql
-- Adds auth failure taxonomy fields for read-only diagnostics and MCP statistics.
-- Existing rows are classified as UNKNOWN_AUTH_ERROR/MEDIUM instead of being guessed.

alter table auth_error
    add column if not exists error_type varchar(50) not null default 'UNKNOWN_AUTH_ERROR',
    add column if not exists auth_failure_severity varchar(10) not null default 'MEDIUM',
    add column if not exists auth_failure_retryable boolean not null default false,
    add column if not exists auth_failure_security_signal boolean not null default false,
    add column if not exists provider varchar(100),
    add column if not exists client_type varchar(50),
    add column if not exists endpoint text,
    add column if not exists principal_hash varchar(64),
    add column if not exists ip_hash varchar(64),
    add column if not exists user_agent_family varchar(100);

alter table auth_error
    add constraint ck_auth_error_type
    check (error_type in (
        'INVALID_CREDENTIALS',
        'TOKEN_EXPIRED',
        'TOKEN_INVALID_SIGNATURE',
        'ACCOUNT_LOCKED',
        'MFA_FAILED',
        'RATE_LIMITED',
        'AUTH_PROVIDER_TIMEOUT',
        'AUTH_PROVIDER_5XX',
        'UNKNOWN_AUTH_ERROR'
    ))
    not valid;

alter table auth_error
    add constraint ck_auth_error_failure_severity
    check (auth_failure_severity in ('LOW', 'MEDIUM', 'HIGH'))
    not valid;

alter table auth_error
    add constraint ck_auth_error_principal_hash_sha256
    check (principal_hash is null or principal_hash ~ '^[0-9a-f]{64}$')
    not valid;

alter table auth_error
    add constraint ck_auth_error_ip_hash_sha256
    check (ip_hash is null or ip_hash ~ '^[0-9a-f]{64}$')
    not valid;

alter table auth_error validate constraint ck_auth_error_type;
alter table auth_error validate constraint ck_auth_error_failure_severity;
alter table auth_error validate constraint ck_auth_error_principal_hash_sha256;
alter table auth_error validate constraint ck_auth_error_ip_hash_sha256;

create index if not exists ix_auth_error_type_time
    on auth_error(error_type, occurred_at desc);

create index if not exists ix_auth_error_provider_type_time
    on auth_error(provider, error_type, occurred_at desc)
    where provider is not null;

create index if not exists ix_auth_error_client_type_time
    on auth_error(client_type, occurred_at desc)
    where client_type is not null;

create index if not exists ix_auth_error_principal_hash_time
    on auth_error(principal_hash, occurred_at desc)
    where principal_hash is not null;

create index if not exists ix_auth_error_ip_hash_time
    on auth_error(ip_hash, occurred_at desc)
    where ip_hash is not null;
