# auth-error-automation Policy (v2)

> 본 문서는 auth-error-automation 시스템의 **단일 정책 기준(Single Source of Truth)** 이다.  
> 이 정책은 사전 설계 문서가 아닌, **현재 코드가 강제하는 규칙을 명시적으로 선언**한 것이다.  
> 이후의 설계 변경·리팩토링·테스트는 반드시 본 문서를 기준으로 판단한다.
>
> 단, 본 문서는 "현재 정책과 구현이 강제하는 범위"를 정의한다. 강한 exactly-once, 자동 DLQ replay, 운영환경 HA, 실부하 SLO 달성은 아직 보장 범위가 아니다.

---

## 0. 현재 보장 범위와 Known Risks

### 현재 보장 범위

- AuthError 생성과 recorded Outbox enqueue는 동일 DB 트랜잭션에서 수행된다.
- Outbox publish는 publisher confirm/return 결과를 확인하고, 실패 시 retry 또는 DEAD 상태로 전이한다.
- Consumer는 at-least-once delivery를 전제로 `processed_message` 원장과 lease claim으로 중복 처리를 제어한다.
- Consumer retry는 RabbitMQ에 직접 publish하지 않고 `retry_publish_request` 원장에 먼저 기록한다.
- DLQ Consumer는 `dead_letter_message` 원장 upsert 이후에만 ACK한다.
- payload 원문은 일반 로그에 남기지 않고 payload hash/size 중심으로 관측한다.

### Known Risks

- DB 트랜잭션과 RabbitMQ publish 사이에 분산 트랜잭션은 없다. Outbox/retry 원장이 복구 지점을 제공하지만, RabbitMQ unavailable/confirm timeout/returned message 장애 주입 증거가 더 필요하다.
- 강한 exactly-once는 보장하지 않는다. 현재 목표는 at-least-once + idempotent side effects다.
- DLQ replay API/worker는 없다. `replay_status`는 운영 판단 상태이며 자동 재처리를 의미하지 않는다.
- `outbox_message.payload_hash`는 payload drift 탐지용이지만 현재 DB 제약상 nullable이다. 과거 row 또는 수동 insert에는 hash가 없을 수 있다.
- DLQ 원장은 payload 원문을 DB에 보관한다. 운영 환경에서는 retention, masking, 접근 통제 정책이 추가로 필요하다.
- single-node local 중심 검증이며 RabbitMQ/PostgreSQL HA, multi-instance ordering, network partition은 아직 별도 검증 대상이다.

---

## 1. 시스템 목적 (Scope)

auth-error-automation은 다음 책임을 가진다.

- 인증(Auth) 오류를 HTTP API로 수집하여 `AuthError` 도메인 엔티티로 영속화한다.
- AuthError 생성과 Outbox 이벤트 기록은 **동일 트랜잭션**에서 수행된다.
- Outbox 패턴을 사용해 이벤트를 발행하고, TTL 기반 재시도 및 DLQ를 관리한다.
- RabbitMQ Consumer는 **at-least-once** 방식으로 메시지를 처리한다.
- Consumer 측 멱등성 및 동시성 제어는 `processed_message` 테이블을 통해 수행한다.
- 분석 단계에서 stacktrace 기반 `stack_hash`를 계산해 AuthError를 클러스터링한다.

---

## 2. 핵심 용어 정리 (Vocabulary)

### AuthError
- 인증 오류 1건을 나타내는 도메인 엔티티
- 상태(`AuthErrorStatus`), 재시도 메타데이터, 요청/예외 컨텍스트를 포함
- `auth_error` 테이블에 저장됨

### OutboxMessage
- 도메인 이벤트 발행을 위한 Outbox 레코드
- payload, eventType, idempotencyKey, 처리 상태를 가짐
- `outbox_message` 테이블에 저장됨

### ProcessedMessage
- Consumer 측에서 OutboxMessage 단위 처리 이력을 관리하는 내부 원장
- `outbox_id` 기준 1:1
- lease 기반으로 동시 처리 방지

### RetryPublishRequest
- Consumer retryable failure 이후 RabbitMQ retry queue 발행 의도를 기록하는 내부 원장
- Consumer는 retry 메시지를 RabbitMQ에 직접 publish하지 않는다.
- `processed_message`의 `RETRY_WAIT` 전이와 RetryPublishRequest 저장은 같은 DB 트랜잭션에서 수행한다.
- 별도 poller가 RetryPublishRequest를 claim한 뒤 publisher confirm/return을 확인하며 retry exchange로 발행한다.

### DeadLetterMessage
- RabbitMQ DLQ에 도착한 메시지를 감사하기 위한 내부 원장
- DLQ Consumer는 payload 원문을 로그로 남기지 않고 payload hash/size, outboxId, reason code만 기록한다.
- DLQ Consumer는 `dead_letter_message` upsert가 성공한 뒤에만 RabbitMQ 메시지를 ACK한다.
- 동일 DLQ 메시지가 중복 delivery되면 `dedupe_key` 기준으로 기존 row의 `delivery_count`, `last_seen_at`을 갱신한다.
- Replay 실행 API/worker는 아직 제공하지 않으며, `replay_status`는 운영 판단 상태만 표현한다.

### EventDescriptor
- Outbox에 저장되는 이벤트 계약
- aggregateType, eventType, idempotencyKey를 정의

### RetryPolicy
- 재시도 여부, 다음 재시도 시점, DEAD 판단을 계산하는 정책 인터페이스
- Outbox/Consumer 양쪽에서 동일 정책을 사용

### Lease
- Consumer가 `processed_message`를 일정 시간 독점 처리하기 위한 시간 제한 클레임
- 기본 60초

### Terminal
- 더 이상 어떤 Consumer/Handler에서도 상태 변경이 허용되지 않는 AuthError 상태

### Decision
- 분석 완료된 AuthError에 대해 운영자/시스템이 내리는 판단
- `DecisionType` → AuthError 상태 전이로 매핑됨

### Cluster
- 동일한 `stack_hash`를 가진 AuthError들의 그룹
- 다대다 연결 구조

---

## 3. 상태 머신 정의 (State Machines)

### 3.1 AuthErrorStatus

#### 상태 목록
```
NEW
RETRY
ANALYSIS_REQUESTED
ANALYSIS_COMPLETED
PROCESSED
FAILED
RESOLVED
IGNORED
```

#### Terminal 상태
```
PROCESSED
FAILED
RESOLVED
IGNORED
```


#### 정책
- Terminal 상태에 진입한 AuthError는 **모든 handler에서 즉시 skip**된다.
- AuthError 상태 전이는 **도메인 메서드를 통해서만** 수행된다.
- out-of-order 이벤트가 도착하더라도 terminal 상태를 우선한다.

---

### 3.2 OutboxMessageStatus

#### 상태 목록
```
PENDING
PROCESSING
PUBLISHED
DEAD
```

#### 정책
- `PENDING` 상태의 메시지만 claim 가능하다.
- claim 시 `PROCESSING + processing_owner`가 설정된다.
- finalize(PUBLISHED/DEAD/RETRY)는 **processing_owner가 일치할 때만** 가능하다.
- 장시간 PROCESSING 상태는 Reaper가 takeover할 수 있다.
- Reaper takeover는 **elapsed time 기준**으로만 판단한다.

---

### 3.3 ProcessedMessageStatus

#### 상태 목록
```
PENDING
PROCESSING
RETRY_WAIT
DONE
DEAD
```

#### 정책 (중요)
- `processed_message`는 **반드시 코드 경로(`ensureRowExists`)를 통해서만 생성된다.**
- DDL default 값은 정책적으로 의미를 갖지 않는다.
- `outbox_id` 기준 **항상 하나의 row만 존재**한다.
- lease 기반으로 중복 Consumer 처리를 방지한다.
- 본 시스템은 **강한 exactly-once를 보장하지 않는다.**
    - 정책적 목표는 *at-least-once + idempotent side effects*이다.

---

## 4. 불변 조건 (Invariants)

- **API 멱등성**
    - `auth_error.dedup_key = requestId` (unique)
- **Outbox 멱등성**
    - `outbox_message.idempotency_key`는 반드시 `authErrorId` 기반이어야 한다.
    - requestId는 **Outbox idempotencyKey**에 사용하지 않는다. (requestId는 API dedup 목적에만 사용)
    - 동일 idempotencyKey는 동일 이벤트 인스턴스를 의미하며, payload 변경은 허용하지 않는다.
- **Consumer 멱등성**
    - `processed_message.outbox_id`는 PK
- **원자성**
    - AuthError 생성 ↔ Outbox enqueue는 동일 트랜잭션
- **Terminal 보호**
    - Terminal AuthError는 모든 handler에서 skip
- **Retry 기준**
    - retry eligibility의 단일 기준은 **DB `next_retry_at`** 이다.
    - Rabbit TTL은 전달 타이밍을 위한 보조 수단이다.

---

## 5. 계약 (Contracts)

### 5.1 API: AuthError 기록

- Endpoint: `POST /api/auth-errors`
- 필수 필드:
    - requestId
    - occurredAt
    - httpStatus
    - exceptionClass
    - stacktrace
- occurredAt:
    - `OffsetDateTime` 파싱 (timezone 포함)
- Sanitization:
    - exception / rootCause message: 최대 1,000자
    - stacktrace: 최대 8,000자
    - 개행 정규화, trim, empty → null

---

### 5.2 Messaging Contract

#### 필수 헤더
- outboxId
- eventType
- aggregateType

#### 정책
- 필수 헤더 누락은 **영구적인 포맷 오류**로 간주한다.
- retry 없이 즉시 DLQ로 보낸다.

#### Payload
- JSON 파싱 실패 → DLQ
- authErrorId 누락 → DLQ
- auth_error 미존재 → DLQ

---

## 6. 이벤트 정의 (Event Types)

### auth.error.recorded.v1
- Producer: AuthErrorWriter
- Payload:
    - authErrorId (필수)
    - requestId (옵션)
    - occurredAt (옵션)
- IdempotencyKey:
    - **IdempotencyKey**: `auth_error:recorded:{authErrorId}`
    - requestId 기반 키는 사용하지 않는다.

### auth.error.analysis.requested.v1
- Producer: AuthErrorRecordHandler
- Payload:
    - authErrorId (필수)
    - requestId, occurredAt, requestedAt (옵션)
- IdempotencyKey:
    - **IdempotencyKey**: `auth_error:analysis_requested:{authErrorId}`
    - requestId 기반 키는 사용하지 않는다.

---

## 7. Retry & DLQ 정책 (Semantics)

- RetryPolicy 기준:
    - retryCount 증가
    - maxRetries 초과 시 DEAD
    - nextRetryAt = now + delay
- Consumer와 Outbox는 동일 RetryPolicy 사용
- Retry Queue:
    - 10s / 1m / 10m TTL ladder
- Consumer retry publish:
    - Consumer는 retryable failure 시 retry publish request를 DB에 먼저 저장한다.
    - 원본 메시지 ACK는 retry publish request가 durable하게 저장된 이후에만 수행한다.
    - retry publish request poller는 RabbitMQ publisher confirm/return을 확인한 뒤 PUBLISHED로 마감한다.
    - retry publish 실패 시 request는 유실하지 않고 재발행 대상으로 남긴다.
    - 단일 retry publish 실패는 원본 `processed_message`를 DEAD로 만들지 않는다.
    - RetryPublishRequest 자체가 terminal `DEAD`가 된 경우에만 원본 `processed_message`를 `DEAD`로 전파한다.
    - 이때 `processed_message.last_error`에는 `RETRY_PUBLISH_REQUEST_DEAD` reason을 남긴다.
- DLQ:
    - Non-retryable 예외
    - maxRetries 초과
    - malformed message / missing headers
    - DLQ 도착 메시지는 `dead_letter_message`에 먼저 기록된 뒤 ACK된다.
    - 원장 저장 실패 시 ACK하지 않아 RabbitMQ 또는 DB 중 적어도 한 곳에 장애 증거가 남아야 한다.
    - 중복 DLQ delivery는 `dedupe_key` unique upsert로 같은 dead letter의 반복 도착으로 기록한다.
    - Reason code는 `DeadLetterReasonCode` enum 값만 사용한다.

---

## 8. 미확정 / 충돌 상태 (CONFLICT)

- (Resolved) Outbox idempotencyKey는 `authErrorId` 기반만 허용하도록 구현을 정합화했다.
  (`auth_error:recorded:{authErrorId}`, `auth_error:analysis_requested:{authErrorId}`)

다음 항목은 **의도적으로 보류된 정책 결정**이다.

- exactly-once 강보장 여부 (D2)
- terminal 이후 analysis/cluster 허용 여부 (D3)
- reaper takeover 조건 강화 여부 (D7)
- DLQ replay API/worker 제공 여부
- `outbox_message.payload_hash`의 backfill 및 NOT NULL 전환 여부
- RabbitMQ publish confirm timeout 이후 중복 publish 가능성을 어느 수준까지 운영 절차로 허용할지

본 시스템은 현재 **운영 자동화·관측 중심** 정책을 따른다.
