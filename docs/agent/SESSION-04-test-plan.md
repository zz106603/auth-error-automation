# SESSION-04 · Policy-Driven Test Plan

본 문서는 `docs/TEST_SCENARIOS.md`에 정의된 **13개 시나리오(TS-01 ~ TS-13)** 를

**정책(invariant) 단위 테스트**로 검증하기 위한 구현 계획이다.

테스트는 다음 3단계로 나누어 진행한다.

- **Phase 1**: 핵심 정책 / 멱등성 / 원자성
- **Phase 2**: 메시징 안정성 (Retry / DLQ)
- **Phase 3**: 상태 전이 가드 / Terminal 보호

> 목표는 “기능 확인”이 아니라
>
>
> **운영 중 절대 깨지면 안 되는 정책을 테스트로 잠그는 것**이다.
>

---

## Phase Overview

| Phase | Focus | Scenarios |
| --- | --- | --- |
| Phase 1 | Core Policy & Idempotency | TS-01, TS-02, TS-03, TS-04, TS-09 |
| Phase 2 | Messaging Safety | TS-08, TS-10, TS-11, TS-12 |
| Phase 3 | State Guards | TS-05, TS-06, TS-07, TS-13 |

---

## Phase 1 · Core Policy & Idempotency

**대상 시나리오:** TS-01, TS-02, TS-03, TS-04, TS-09

→ 시스템의 **데이터 무결성**과 **중복 처리 안전성**을 보장한다.

---

### TS-01 · API Idempotency (requestId)

**목적**

동일한 `requestId` 요청은 **항상 하나의 AuthError만 생성**되어야 한다.

- **Test Class**

  `AuthErrorIdempotencyIntegrationTest` (new)

- **핵심 검증**
    - 동일 `requestId` 중복 호출 → `auth_error` row = **1**
    - 두 번째 호출:
        - Controller 경유 시 5xx 미발생
        - 최소 동일 `authErrorId` 반환
- **재사용**
    - `AbstractIntegrationTest`
    - `newTestCommand()` 패턴
    - `AuthErrorStore` 또는 `JdbcTemplate`

---

### TS-02 · Atomicity (AuthError + Recorded Outbox)

**목적**

AuthError 저장과 Recorded Outbox는 **항상 원자적으로 처리**되어야 한다.

- **Test Class**

  `AuthErrorRecordAtomicityIntegrationTest` (new)

- **검증 포인트 (Fail Injection)**
    - 저장 전
    - 저장 후 / Outbox 전
    - Outbox 후 / Commit 전
- **허용 상태**
    - 둘 다 존재
    - 둘 다 없음
- **금지 상태**
    - `auth_error`만 존재 + outbox 없음 ❌
- **재사용**
    - `TestFailInjectionConfig`
    - `FailInjectedAuthErrorHandler` 패턴

---

### TS-03 / TS-04 · Outbox Idempotency (event + authErrorId)

**목적**

동일 이벤트 + 동일 `authErrorId` 조합은 **Outbox row 1개만 허용**한다.

- **Test Class**

  `OutboxWriterIntegrationTest` (modify)

- **검증**
    - `auth_error:recorded:{authErrorId}` → row = 1
    - `auth_error:analysis_requested:{authErrorId}` → row = 1
    - idempotency key 문자열 포맷 정확성
- **재사용**
    - 기존 Outbox 멱등성 테스트 구조
    - Descriptor만 실제 정책 기준으로 교체

---

### TS-09 · Consumer Idempotency (processed_message)

**목적**

중복 Delivery 상황에서도 **processed_message는 outbox_id 기준 1건만 생성**되어야 한다.

- **Test Class**

  `AuthErrorPipelineFailureIntegrationTest` (modify)

- **검증**
    - Duplicate Delivery 이후 `processed_message(outbox_id)` row = 1
    - 동시성 상황에서도 중복 insert 없음
- **재사용**
    - `DuplicateDeliveryInjector`
    - 기존 중복 수신 테스트 구조

---

## Phase 2 · Messaging Safety (Retry / DLQ)

> 메시지 손실, 무한 재시도, 잘못된 재처리를 방지한다.
>

### 대상 시나리오

- TS-08 Retry / Dead 분기
- TS-10 Retry Gate (`next_retry_at`)
- TS-11 Contract Violation → DLQ
- TS-12 Payload Poison → DLQ

(※ Phase 1 안정화 이후 진행)

---

## Phase 3 · State Guards & Terminal Protection

> 상태 전이 오류와 **Terminal 상태 오염**을 방지한다.
>

### 대상 시나리오

- TS-05 Recorded Handler Idempotency
- TS-06 Decision Guard
- TS-07 Analysis Request Atomicity
- TS-13 Terminal State Skip

---

## Naming & DisplayName Convention (중요)

- **메서드명**: 행위 중심, 시나리오 번호 ❌
- **@DisplayName**: 시나리오 번호 포함 ⭕

```java
@DisplayName("[TS-01] 동일 requestId 중복 기록 시 auth_error는 1건만 생성된다")@Testvoid 동일_requestId_중복_기록_시_auth_error_1건만_생성된다() { }
```

→ 테스트 실행 결과에서 **정책 단위로 즉시 인식 가능**

---

## Risk & Stability Notes

### 공통 원칙

- `Thread.sleep` 금지
- DB 상태 기반 검증
- `Awaitility`로 결정적 폴링

### Phase 1 주요 리스크

- 비동기 파이프라인 간섭

  → DB 정리 or 명시적 초기화

- 트랜잭션 경계 외 Fail Injection

  → 반드시 경계 내부에서 주입


---

## 최종 평가

- 이 문서는 **“테스트 계획”이 아니라 “정책 고정 문서”**에 가깝다.
- Phase 1만 완료해도:
    - 멱등성
    - 원자성
    - 중복 소비 방지

      가 테스트로 보장된다.