# SESSION-01 Policy Review (code-derived)

## 0. 목적
- 코드에서 역추출한 `docs/POLICY.md`를 단일 정책 기준(Single Source of Truth)으로 확정한다.
- “정책 깨기(Break Scenarios)” 결과를 바탕으로, 정책 레벨에서 명시적 결정이 필요한 지점을 정리한다.
- 본 세션에서는 **코드 수정은 수행하지 않는다.** (정책 결정만 기록)

## 1. 입력
- POLICY.md (v1, code-derived)
- Break Scenarios (Top 10, Codex adversarial review)

---

## 2. 정책 결정 포인트 (Decision Log)

### D1. ProcessedMessage 초기 상태의 권위
- 배경  
  `processed_message`의 초기 상태가 DDL(default = PROCESSING)과 코드(`ensureRowExists` → PENDING)로 이원화되어 있음.
- 관련 리스크  
  [S1/L5] default state mismatch로 exactly-once 보장 우회 가능
- 결정  
  **✅ 채택**
- 정책 결정
    - `processed_message`는 **코드 경로(`ensureRowExists`)를 통해서만 생성된다.**
    - DDL default 값은 정책적으로 의미를 갖지 않으며, 권위 있는 초기 상태는 코드에서 설정한 값이다.
- 영향(POLICY.md)
    - ProcessedMessageStatus
    - Invariants
    - CONFLICTS 설명 보강
- 후속
    - 문서 보강만 수행

---

### D2. Lease 만료 중 handler 실행 시 exactly-once 보장 수준
- 배경  
  lease(60s) 만료 후 재claim이 가능하며, handler 실행 시간이 길 경우 중복 side-effect 가능.
- 관련 리스크  
  [S2/L5] lease expiry 중복 side effects
- 결정  
  **🟨 보류**
- 정책 판단
    - 본 시스템은 “강한 exactly-once”를 보장하지 않는다.
    - 정책적으로는 **at-least-once + idempotent side effects**를 목표로 한다.
- 영향(POLICY.md)
    - Invariants (exactly-once 정의 문구)
- 후속
    - handler side-effect 범위가 커질 경우 재논의

---

### D3. Terminal-skip과 out-of-order 이벤트 처리
- 배경  
  terminal 상태의 AuthError는 analysis handler에서 즉시 skip되어 분석 결과/클러스터링이 누락될 수 있음.
- 관련 리스크  
  [S3/L4], [S10/L2] terminal-skip으로 분석 누락
- 결정  
  **🟨 보류**
- 정책 판단
    - 현행 정책(terminal이면 analysis/cluster skip)을 유지한다.
    - terminal은 “업무적으로 종료된 상태”를 의미한다.
- 영향(POLICY.md)
    - AuthErrorStatus
    - Terminal 정의
- 후속
    - 운영 시나리오가 구체화되면 재검토

---

### D4. Outbox idempotencyKey 기준 (requestId vs authErrorId)
- 배경  
  requestId 기반 idempotencyKey 사용 시 서로 다른 AuthError 간 충돌 가능.
- 결정  
  **✅ 채택**
- 정책 결정
    - 이벤트 인스턴스의 권위 식별자는 **authErrorId**이다.
    - requestId는 보조 식별자이며, fallback 용도로만 사용된다.
- 영향(POLICY.md)
    - Event Types
    - Invariants (idempotency boundary)
- 후속
    - 문서에서 fallback 규칙 명확화

---

### D5. Retry eligibility 기준 (Rabbit TTL vs DB next_retry_at)
- 배경  
  Rabbit TTL과 DB `next_retry_at` 간 불일치 시 retry가 차단될 수 있음.
- 결정  
  **✅ 채택**
- 정책 결정
    - **DB `next_retry_at`이 retry eligibility의 단일 기준이다.**
    - Rabbit TTL은 전달 타이밍을 위한 보조 수단이다.
- 영향(POLICY.md)
    - Retry & DLQ Semantics
    - ProcessedMessageStatus claim 규칙
- 후속
    - 문서 명시

---

### D6. Missing header 처리 정책
- 배경  
  필수 헤더 누락 시 즉시 DLQ 처리.
- 결정  
  **✅ 채택**
- 정책 결정
    - 필수 헤더 누락은 **영구적인 포맷 오류**로 간주한다.
    - retry 없이 즉시 DLQ로 보낸다.
- 영향(POLICY.md)
    - Messaging Contract
    - DLQ 정책
- 후속
    - 없음 (정책 확정)

---

### D7. Outbox Reaper takeover 조건
- 배경  
  Reaper가 elapsed time 기준으로 PROCESSING 메시지를 takeover 가능.
- 결정  
  **🟨 보류**
- 정책 판단
    - 현행 정책(시간 기준 takeover)을 유지한다.
    - 추가적인 liveness/heartbeat 조건은 도입하지 않는다.
- 영향(POLICY.md)
    - OutboxMessageStatus
    - Reaper semantics
- 후속
    - 필요 시 재검토

---

### D8. Outbox upsert 시 payload/event_type 미갱신
- 배경  
  동일 idempotencyKey로 다른 payload가 들어오면 stale payload 가능.
- 결정  
  **✅ 채택**
- 정책 결정
    - 동일 idempotencyKey는 **동일 이벤트, 동일 payload**를 의미한다.
    - payload 변경이 필요한 경우는 정책 위반이다.
- 영향(POLICY.md)
    - Outbox invariants
    - Event contract
- 후속
    - 문서에 “의도된 동작”으로 명시

---

## 3. 세션 요약

### 채택(✅)
- D1, D4, D5, D6, D8

### 보류(🟨)
- D2, D3, D7

### 거절(❌)
- 없음

---

## 4. 다음 액션
- (A) SESSION-01 결정을 반영하여 POLICY.md v2 작성
- (B) 보류 항목(D2, D3, D7)에 대해 추가 시나리오/운영 가정 수집
- (C) 이후 필요 시에만 코드 변경 착수
