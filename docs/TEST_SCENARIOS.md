# Test Scenarios (Policy-driven)

본 문서는 `docs/POLICY.md`와  
`docs/agent/SESSION-01-policy-review.md`에서 확정/보류된 정책을 기준으로,

**“이 시스템이 반드시 지켜야 하는 동작을 테스트 관점에서 선언”** 하기 위한 문서이다.

- 구현 방법, 테스트 프레임워크, 코드 구조는 다루지 않는다.
- 각 시나리오는 **정책을 깨뜨릴 수 있는 상황**을 기준으로 작성된다.
- 본 문서는 이후 통합 테스트/부하 테스트의 기준점으로 사용된다.

---

## TS-01 API 멱등성: requestId 기반 AuthError 중복 방지

### 정책 근거
- POLICY.md §4 Invariants / API 멱등성
- dedupKey = requestId (unique)

### Given
- 동일한 `requestId`로 API 요청이 여러 번(동시 또는 순차) 들어온다.

### When
- AuthError 기록 API가 반복 호출된다.

### Then
- `auth_error`는 **1건만 생성**되어야 한다.
- 중복 요청으로 인해 추가 row가 생성되면 안 된다.
- 서버는 불필요한 5xx 에러를 반환하지 않아야 한다.
- 관측 포인트:
    - DB unique constraint 위반이 “정상적인 멱등 흐름”으로 처리되는지
    - 동일 requestId에 대해 auth_error row 수가 1로 유지되는지

---

## TS-02 원자성: AuthError 저장과 Recorded Outbox는 함께 커밋된다

### 정책 근거
- POLICY.md §4 Invariants / 원자성

### Given
- API를 통해 AuthError 기록 요청이 들어온다.

### When
- 다음 지점 중 하나에서 예외가 발생한다고 가정한다.
    1) AuthError 저장 이전
    2) AuthError 저장 이후, Outbox 저장 이전
    3) Outbox 저장 이후, 트랜잭션 커밋 이전

### Then
- 커밋 기준으로 다음 상태만 허용된다.
    - AuthError + recorded outbox **둘 다 존재**
    - AuthError + recorded outbox **둘 다 없음**
- AuthError만 존재하고 recorded outbox가 없는 상태가 남으면 안 된다.
- 관측 포인트:
    - auth_error ↔ outbox_message(recorded) 간 1:1 대응 관계

---

## TS-03 Outbox 멱등성: recorded 이벤트는 authErrorId 기준으로 1회만 생성된다

### 정책 근거
- POLICY.md §4 Invariants / Outbox 멱등성
- POLICY.md §6 Event Types / auth.error.recorded.v1
- SESSION-01 D4

### Given
- 동일한 `authErrorId`에 대해 recorded 이벤트가 중복 발생한다.

### When
- Outbox upsert 로직이 여러 번 실행된다.

### Then
- recorded outbox_message는 **1건만 존재**해야 한다.
- idempotencyKey는 `auth_error:recorded:{authErrorId}` 형식이어야 한다.
- requestId 재사용으로 서로 다른 AuthError가 충돌하면 안 된다.
- 관측 포인트:
    - outbox_message row 수
    - idempotency_key 값의 안정성

---

## TS-04 Outbox 멱등성: analysis_requested 이벤트도 authErrorId 기준으로 1회만 생성된다

### 정책 근거
- POLICY.md §6 Event Types / auth.error.analysis_requested.v1
- SESSION-01 D4

### Given
- 동일한 `authErrorId`에 대해 analysis_requested 이벤트가 중복 생성된다.

### When
- Outbox upsert가 반복 수행된다.

### Then
- analysis_requested outbox_message는 **1건만 존재**해야 한다.
- idempotencyKey는 `auth_error:analysis_requested:{authErrorId}` 형식이어야 한다.
- 관측 포인트:
    - recorded 단계와 동일한 멱등성 보장 여부

---

## TS-05 Recorded Handler 동작 제한: ANALYSIS_REQUESTED 상태에서는 재요청하지 않는다

### 정책 근거
- POLICY.md §3.1 AuthErrorStatus
- SESSION-02 Finding F2 (채택)

### Given
- AuthError 상태가 이미 `ANALYSIS_REQUESTED` 이다.

### When
- recorded 이벤트가 다시 전달된다(중복 delivery).

### Then
- analysis_requested 이벤트를 **다시 생성하지 않아야 한다.**
- AuthError 상태는 변경되지 않아야 한다.
- 관측 포인트:
    - “already analysis requested → skip” 로그
    - analysis_requested outbox 중복 생성 여부

---

## TS-06 Decision 적용 제한: ANALYSIS_COMPLETED 상태에서만 허용된다

### 정책 근거
- POLICY.md §3.1 AuthErrorStatus
- SESSION-02 Finding F1 (채택)

### Given
- AuthError 상태가 `ANALYSIS_COMPLETED`가 아닌 상태이다.

### When
- Decision(PROCESS / RETRY / IGNORE / RESOLVE / FAIL)를 적용하려고 시도한다.

### Then
- Decision 적용은 거부되어야 한다.
- AuthError 상태는 변경되지 않아야 한다.
- 관측 포인트:
    - 허용/거부 상태 전이 로그
    - 잘못된 상태 전이 발생 여부

---

## TS-07 Analysis 요청의 원자성: 상태 전이와 Outbox 생성은 함께 이뤄진다

### 정책 근거
- POLICY.md §4 Invariants / 원자성
- SESSION-02 Finding F3 (채택)

### Given
- recorded 이벤트 처리 중 analysis 요청을 생성하는 단계이다.

### When
- 다음 지점에서 예외가 발생한다고 가정한다.
    - 상태를 `ANALYSIS_REQUESTED`로 바꾸기 전
    - 상태 변경 후 outbox enqueue 전

### Then
- 다음 상태만 허용된다.
    - 상태 변경 + analysis_requested outbox **둘 다 성공**
    - 상태 변경 + analysis_requested outbox **둘 다 실패**
- 상태만 바뀌고 outbox가 없는 상태가 남으면 안 된다.
- 관측 포인트:
    - auth_error 상태
    - analysis_requested outbox 존재 여부

---

## TS-08 Outbox publish 실패 처리: retry와 dead 분기

### 정책 근거
- POLICY.md §Retry & DLQ Semantics
- SESSION-02 Finding F4 (채택)

### Given
- outbox_message가 PROCESSING 상태이다.

### When
- publish 과정에서
    - retry 가능한 예외
    - retry 불가능한 예외
      가 각각 발생한다.

### Then
- retry 가능한 경우:
    - outbox는 재시도 경로로 이동해야 한다.
- retry 불가능한 경우:
    - outbox는 DEAD 상태로 이동해야 한다.
- 관측 포인트:
    - outbox 상태 전이
    - retry_count / DEAD 여부

---

## TS-09 Consumer 멱등성: processed_message는 outbox_id 기준 1건만 존재한다

### 정책 근거
- POLICY.md §3.3 ProcessedMessageStatus
- POLICY.md §4 Invariants / Consumer 멱등성

### Given
- 동일한 outbox 메시지가 중복 delivery 된다(at-least-once).

### When
- Consumer가 processed_message를 생성/claim 한다.

### Then
- processed_message는 outbox_id 기준으로 **1건만 존재**해야 한다.
- 중복 delivery로 새로운 row가 생성되면 안 된다.
- 관측 포인트:
    - processed_message row 수
    - PK/unique constraint 동작

---

## TS-10 Retry 기준: DB next_retry_at 단일 기준

### 정책 근거
- POLICY.md §4 Invariants / Retry 기준
- SESSION-01 D5

### Given
- processed_message 상태가 `RETRY_WAIT(next_retry_at = 미래 시점)` 이다.

### When
- 메시지가 Rabbit TTL로 재전달된다.

### Then
- 현재 시간이 next_retry_at 이전이면 처리는 차단되어야 한다.
- 현재 시간이 next_retry_at 이후이면 처리가 허용되어야 한다.
- 관측 포인트:
    - claim 성공/실패 로그
    - RETRY_WAIT → PROCESSING 전이 시점

---

## TS-11 계약 위반 메시지: missing header는 즉시 DLQ + 무부작용

### 정책 근거
- POLICY.md §5.2 Messaging Contract
- SESSION-01 D6
- SESSION-02 Finding F7 (보강)

### Given
- outboxId / eventType / aggregateType 중 하나 이상이 누락된 메시지이다.

### When
- Consumer가 메시지를 수신한다.

### Then
- retry 없이 즉시 DLQ로 보내야 한다.
- processed_message, AuthError 상태 등 **어떤 내부 상태도 변경되면 안 된다.**
- 관측 포인트:
    - DLQ 적재 여부
    - processed_message 생성 흔적 유무

---

## TS-12 Payload 파싱 실패: poison message는 즉시 DLQ

### 정책 근거
- POLICY.md §5.2 Messaging Contract
- SESSION-02 Finding F6 (채택)

### Given
- payload가 JSON 파싱 불가이거나 authErrorId가 누락되어 있다.

### When
- Consumer가 메시지를 처리한다.

### Then
- retry 없이 즉시 DLQ로 보내야 한다.
- handler 비즈니스 로직은 수행되지 않아야 한다.
- 관측 포인트:
    - 파싱 실패 로그
    - DLQ 적재 여부
    - processed_message 생성 여부

---

## TS-13 Terminal 상태 보호: terminal AuthError는 재처리되지 않는다

### 정책 근거
- POLICY.md §3.1 AuthErrorStatus / Terminal skip
- SESSION-01 D3 (보류, 현행 유지)

### Given
- AuthError 상태가 terminal(PROCESSED/FAILED/RESOLVED/IGNORED)이다.

### When
- recorded / analysis_requested / analysis_completed 이벤트가 뒤늦게 도착한다.

### Then
- handler는 즉시 skip해야 한다.
- AuthError 상태는 변경되면 안 된다.
- 관측 포인트:
    - “terminal → skip” 로그
    - 추가 상태 전이/부작용 여부

---
