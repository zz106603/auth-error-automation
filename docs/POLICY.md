# auth-error-automation Policy (v2)

> 본 문서는 auth-error-automation 시스템의 **단일 정책 기준(Single Source of Truth)** 이다.  
> 이 정책은 사전 설계 문서가 아닌, **현재 코드가 강제하는 규칙을 명시적으로 선언**한 것이다.  
> 이후의 설계 변경·리팩토링·테스트는 반드시 본 문서를 기준으로 판단한다.

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
- DLQ:
    - Non-retryable 예외
    - maxRetries 초과
    - malformed message / missing headers

---

## 8. 미확정 / 충돌 상태 (CONFLICT)

- (Resolved) Outbox idempotencyKey는 `authErrorId` 기반만 허용하도록 구현을 정합화했다.
  (`auth_error:recorded:{authErrorId}`, `auth_error:analysis_requested:{authErrorId}`)

다음 항목은 **의도적으로 보류된 정책 결정**이다.

- exactly-once 강보장 여부 (D2)
- terminal 이후 analysis/cluster 허용 여부 (D3)
- reaper takeover 조건 강화 여부 (D7)

본 시스템은 현재 **운영 자동화·관측 중심** 정책을 따른다.
