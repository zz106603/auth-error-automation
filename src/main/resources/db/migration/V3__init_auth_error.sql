-- V3__init_auth_error.sql
-- PostgreSQL

create table if not exists auth_error (
    id                  bigserial primary key,

    -- ===== 식별/추적 =====
    request_id           varchar(100),                 -- 외부 요청/업무 단위 ID (있으면 강력)
    correlation_id       varchar(100),                 -- 시스템 전반 상관관계 ID
    trace_id             varchar(64),                  -- distributed tracing (W3C traceparent 등)
    span_id              varchar(16),

    -- ===== 발생 시점/출처 =====
    occurred_at          timestamptz not null default now(),
    received_at          timestamptz not null default now(), -- 우리 시스템이 수집/저장한 시간
    source_service       varchar(100) not null,             -- e.g. auth-api, gateway
    source_instance      varchar(100),                      -- host/pod id
    environment          varchar(20) not null default 'prod', -- dev/stage/prod 등

    -- ===== 요청 컨텍스트 (운영 조회 핵심) =====
    http_method          varchar(10),
    request_uri          text,
    client_ip            varchar(50),
    user_agent           text,

    -- 사용자/세션(PII는 최소화 권장)
    user_id              varchar(100),
    session_id           varchar(200),

    -- ===== 에러 분류(검색/대시보드용) =====
    error_domain         varchar(50) not null default 'AUTH',   -- AUTH, SECURITY, SYSTEM 등
    error_code           varchar(100),                          -- 사내 표준 코드 (권장)
    severity             varchar(10) not null default 'ERROR',  -- INFO/WARN/ERROR/CRITICAL
    category             varchar(50),                           -- INVALID_TOKEN, EXPIRED, FORBIDDEN ...

    -- ===== 예외/원인(디버깅) =====
    exception_class      varchar(200),
    exception_message    text,
    root_cause_class     varchar(200),
    root_cause_message   text,

    -- stacktrace는 크고 민감할 수 있어서 보관 정책 필요
    stacktrace           text,

    -- ===== 원본 데이터(유연성/재현) =====
    request_headers      jsonb,      -- 필요시 일부만(민감 헤더 제거)
    request_body         jsonb,      -- 가능하면 마스킹/부분 저장
    extra_context        jsonb,      -- securityContext, claims, internal vars 등
    tags                 jsonb,      -- ["jwt","gateway","timeout"] 같은 태그

    -- ===== 처리 상태(재처리/워크플로우) =====
    status               varchar(20) not null default 'NEW',
    retry_count          int not null default 0,
    next_retry_at        timestamptz,
    last_processed_at    timestamptz,
    resolved_at          timestamptz,
    resolution_note      text,

    -- ===== 중복 방지/집계키 =====
    dedup_key            varchar(64),     -- sha256 같은 해시 문자열 권장

    created_at           timestamptz not null default now(),
    updated_at           timestamptz not null default now()
);

-- status 값 제한(ENUM 대신 체크 제약)
alter table auth_error
    add constraint ck_auth_error_status
        check (status in ('NEW','RETRY','PROCESSED','FAILED','RESOLVED','IGNORED'));

-- 중복 방지
create unique index if not exists ux_auth_error_dedup_key
    on auth_error(dedup_key)
    where dedup_key is not null;

-- 조회 패턴 인덱스
create index if not exists ix_auth_error_occurred_at
    on auth_error(occurred_at desc);

create index if not exists ix_auth_error_status_next_retry
    on auth_error(status, next_retry_at)
    where status in ('NEW','RETRY');

create index if not exists ix_auth_error_request_id
    on auth_error(request_id)
    where request_id is not null;

create index if not exists ix_auth_error_trace_id
    on auth_error(trace_id)
    where trace_id is not null;

create index if not exists ix_auth_error_service_env_time
    on auth_error(source_service, environment, occurred_at desc);