# auth-error-automation — Policy Definition (v1, code-derived)

> 본 문서는 **사전 설계 문서가 없는 상태에서**,
>
>
> 현재 코드가 **사실상 강제하고 있는 규칙**을 정책으로 선언한 것이다.
>
> 이 문서는 이후 설계 변경, 리팩토링, 정책 검증의 **단일 기준(Single Source of Truth)** 이 된다.
>

---

## 1. 시스템 목적 (Scope)

auth-error-automation은 다음 책임을 가진다.

- 인증(Auth) 오류를 HTTP API로 수집하고, **AuthError 도메인 엔티티로 영속화**한다.
- AuthError 생성과 **Outbox 이벤트 기록을 동일 트랜잭션에서 수행**한다.
- Outbox 패턴을 통해 이벤트를 발행하고, **TTL 기반 재시도 및 DLQ를 관리**한다.
- RabbitMQ Consumer는 **at-least-once**로 메시지를 처리하며, `processed_message`를 통해 멱등성과 동시성 제어를 수행한다.
- 분석 단계에서 stacktrace 기반 `stack_hash`를 계산해 **클러스터링**한다.

---

## 2. 핵심 용어 정의 (Vocabulary)

### AuthError

- 인증 오류 1건을 나타내는 도메인 엔티티
- 상태(`AuthErrorStatus`), 재시도 메타데이터, 요청/예외 컨텍스트를 포함
- `auth_error` 테이블에 저장됨

### OutboxMessage

- 도메인 이벤트 발행을 위한 Outbox 레코드
- payload, eventType, idempotencyKey, 처리 상태를 가짐
- `outbox_message` 테이블에 저장됨

### ProcessedMessage

- Consumer 측에서 **OutboxMessage 단위 처리 이력을 관리하는 원장**
- `outbox_id` 기준 1:1
- lease 기반으로 동시 처리 방지

### EventDescriptor

- Outbox에 저장되는 이벤트 계약
- aggregateType, eventType, idempotencyKey를 정의

### RetryPolicy

- 재시도 여부, 다음 재시도 시점, DEAD 판단을 계산하는 정책 인터페이스
- Outbox/Consumer 양쪽에서 동일 정책 사용

### Lease

- Consumer가 `processed_message`를 일정 시간 독점 처리하기 위한 시간 제한 클레임
- 기본 60초

### Terminal

- **더 이상 어떤 Consumer/Handler에서도 상태 변경이 허용되지 않는 AuthError 상태**

### Decision

- 분석 완료된 AuthError에 대해 운영자/시스템이 내리는 판단
- `DecisionType` → AuthError 상태 전이로 매핑됨

### Cluster

- 동일한 `stack_hash`를 가진 AuthError들의 그룹
- 다대다 연결 구조

---

## 3. 상태 머신 정의 (State Machines)

### 3.1 AuthErrorStatus

### 상태 목록 (권위 있는 정의)

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

### Terminal 상태

```
PROCESSED
FAILED
RESOLVED
IGNORED
```

### 핵심 정책

- Terminal 상태에 진입한 AuthError는 **어떤 Consumer에서도 다시 처리되지 않는다**
- 상태 전이는 **도메인 메서드를 통해서만** 수행된다

### 주요 전이 요약

- NEW → ANALYSIS_REQUESTED

  (recorded 이벤트 처리 성공 시)

- ANALYSIS_REQUESTED → ANALYSIS_COMPLETED

  (analysis handler 성공 시)

- ANALYSIS_COMPLETED → {PROCESSED | RETRY | FAILED | RESOLVED | IGNORED}

  (Decision 적용 시)

- 비정상/예외 상황에서 RETRY 또는 FAILED로 전이 가능

---

### 3.2 OutboxMessageStatus

### 상태 목록

```
PENDING
PROCESSING
PUBLISHED
DEAD
```

### 핵심 정책

- PENDING 메시지만 claim 가능
- claim 시 `PROCESSING + processing_owner` 설정
- finalize(PUBLISHED/DEAD/RETRY)는 **owner 일치 조건** 하에서만 가능
- 장시간 PROCESSING 상태는 **Reaper가 takeover** 가능

---

### 3.3 ProcessedMessageStatus

### 상태 목록

```
PENDING
PROCESSING
RETRY_WAIT
DONE
DEAD
```

### 핵심 정책

- `outbox_id` 기준 **항상 하나의 row만 존재**
- lease 기반으로 중복 Consumer 처리 방지
- lease 만료 시 다른 Consumer가 재claim 가능
- retry는 RETRY_WAIT + next_retry_at 으로 표현

⚠️ **초기 상태(PENDING vs PROCESSING)는 코드/DDL 간 불일치 → 정책 미확정 상태**

---

## 4. 불변 조건 (Invariants)

이 시스템에서 **항상 성립해야 하는 조건**:

- API 레벨 멱등성
    - `auth_error.dedup_key = requestId` (unique)
- Outbox 멱등성
    - `outbox_message.idempotency_key` unique + upsert
- Consumer 멱등성
    - `processed_message.outbox_id` PK
- 트랜잭션 원자성
    - AuthError 생성 ↔ Outbox enqueue는 동일 트랜잭션
- Terminal 보호
    - Terminal AuthError는 모든 handler에서 즉시 skip
- 상태 제약
    - AuthError / Outbox 상태는 DB check constraint로 제한됨

---

## 5. 계약 (Contracts)

### 5.1 API — AuthError 기록

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

### 5.2 Messaging — Consumer 계약

### 필수 헤더

- outboxId
- eventType
- aggregateType

→ 누락 시 **Non-retryable → DLQ**

### Payload 공통 정책

- JSON 파싱 실패 → DLQ
- authErrorId 누락 → DLQ
- auth_error 미존재 → DLQ

---

## 6. 이벤트 정의 (As Implemented)

### auth.error.recorded.v1

- Producer: AuthErrorWriter
- Payload:
    - authErrorId (필수)
    - requestId (옵션)
    - occurredAt (옵션)
- IdempotencyKey:
    - requestId 우선, 없으면 authErrorId fallback

### auth.error.analysis.requested.v1

- Producer: AuthErrorRecordHandler
- Payload:
    - authErrorId (필수)
    - requestId, occurredAt, requestedAt (옵션)
- IdempotencyKey:
    - requestId 우선, 없으면 authErrorId fallback

---

## 7. Retry & DLQ 정책

- RetryPolicy 기준:
    - retryCount 증가
    - maxRetries 초과 시 DEAD
    - nextRetryAt = now + delay
- Consumer와 Outbox는 **동일 RetryPolicy 사용**
- Retry Queue:
    - 10s / 1m / 10m TTL ladder
- DLQ:
    - Non-retryable 예외
    - maxRetries 초과
    - malformed message

---

## 8. 미확정 / 충돌 상태 (CONFLICTS)

아래 항목은 **정책 결정이 아직 내려지지 않은 상태**이며, 명시적 선택이 필요하다.

1. ProcessedMessage 초기 상태
    - DDL: default = PROCESSING
    - 코드: ensure 시 PENDING
2. Outbox max_retries 컬럼
    - 스키마 존재
    - 실제 로직은 Properties 기반 → 컬럼 미사용