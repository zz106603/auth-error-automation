# SESSION-02 Test Scenario Review (Policy-driven)

## 0. 목적
본 세션은 `docs/TEST_SCENARIOS.md`에 정의된 테스트 시나리오 초안을
정책 관점에서 검증하고, 누락되었거나 불충분한 테스트 의도를 보완하기 위해 수행되었다.

- 입력:
    - docs/POLICY.md
    - docs/TEST_SCENARIOS.md
    - docs/agent/SESSION-01-policy-review.md
- 검토 방식:
    - Codex를 QA / Adversarial Reviewer로 사용
    - 코드 구현이 아닌 **정책 강제 관점의 테스트 의도**만 검토

---

## 1. Codex Review 요약

Codex는 총 **8개의 주요 Finding**을 제시했으며,
대부분이 **정책 핵심 불변식을 직접 강제하는 테스트 시나리오 누락**에 해당한다.

- Missing Scenario: 6건
- Weak Scenario: 1건
- (중복/저가치 제안은 없음)

---

## 2. Finding별 판단 및 결정

### F1. Decision 적용 상태 제한 누락
- 유형: Missing Scenario
- 정책 근거:
    - POLICY.md §State Machines / AuthErrorStatus
- 이슈 요약:
    - Decision(PROCESS/RETRY/IGNORE/RESOLVE/FAIL)은
      `ANALYSIS_COMPLETED` 상태에서만 허용된다는 핵심 전이 규칙을
      검증하는 시나리오가 없음.
- 결정: **✅ 채택**
- 조치:
    - `TS-XX Decision Apply Only From ANALYSIS_COMPLETED` 시나리오 추가
- 이유:
    - 잘못된 상태 전이는 도메인 무결성을 직접적으로 깨뜨리는 고위험 요소

---

### F2. Recorded Handler의 ANALYSIS_REQUESTED skip 규칙 누락
- 유형: Missing Scenario
- 정책 근거:
    - POLICY.md §State Machines / AuthErrorStatus
- 이슈 요약:
    - terminal은 아니지만 `ANALYSIS_REQUESTED` 상태에서
      recorded 이벤트를 skip해야 하는 규칙이 테스트로 고정되어 있지 않음.
- 결정: **✅ 채택**
- 조치:
    - `TS-XX Recorded Handler Skips ANALYSIS_REQUESTED` 시나리오 추가
- 이유:
    - 중복 analysis 요청 생성은 실제로 비용/노이즈를 유발할 수 있음

---

### F3. Analysis Request Atomicity 검증 누락
- 유형: Missing Scenario
- 정책 근거:
    - POLICY.md §Invariants / Atomicity
- 이슈 요약:
    - analysis-requested outbox enqueue와
      AuthError 상태 업데이트 간 원자성이 테스트로 보장되지 않음.
- 결정: **✅ 채택**
- 조치:
    - `TS-XX Analysis Request Atomicity` 시나리오 추가
- 이유:
    - 부분 성공 상태는 “영구 정체(auth_error는 ANALYSIS_REQUESTED인데 outbox 없음)”로 이어질 수 있음

---

### F4. Outbox publish 실패 시 상태 전이 규칙 누락
- 유형: Missing Scenario
- 정책 근거:
    - POLICY.md §Retry & DLQ Semantics
    - POLICY.md §State Machines / OutboxMessageStatus
- 이슈 요약:
    - publish 실패가 retryable/non-retryable일 때
      outbox 상태가 어떻게 달라지는지 테스트되지 않음.
- 결정: **✅ 채택**
- 조치:
    - `TS-XX Outbox Publish Failure Handling` 시나리오 추가
- 이유:
    - DEAD vs RETRY 분기 오류는 메시지 유실로 직결됨

---

### F5. Retry Routing Ladder(x-retry-count) 검증 누락
- 유형: Missing Scenario
- 정책 근거:
    - POLICY.md §Retry & DLQ Semantics
    - SESSION-01 D5
- 이슈 요약:
    - retry-count에 따른 10s/1m/10m 큐 라우팅이 테스트로 고정되어 있지 않음.
- 결정: **🟨 보류**
- 이유:
    - 브로커 설정 중심이며, 통합 테스트 비용 대비 리턴이 낮음
    - 운영 장애 발생 시에만 보강 고려

---

### F6. Payload 파싱 실패(non-retryable) 시나리오 누락
- 유형: Missing Scenario
- 정책 근거:
    - POLICY.md §Contracts / Messaging
- 이슈 요약:
    - malformed JSON / missing authErrorId가 즉시 DLQ로 가야 한다는 규칙이 테스트되지 않음.
- 결정: **✅ 채택**
- 조치:
    - `TS-XX Payload Parse Failures to DLQ` 시나리오 추가
- 이유:
    - Poison message 방어는 운영 안정성의 기본선

---

### F7. Missing Headers 시 processed_message 부작용 모호성
- 유형: Weak Scenario
- 정책 근거:
    - POLICY.md §Contracts / Messaging
    - SESSION-01 D6
- 이슈 요약:
    - TS-11이 “processed_message는 정책에 따름”으로 남아 있어,
      실제로는 **아무 side-effect도 없어야 한다는 정책**을 충분히 강제하지 못함.
- 결정: **✅ 채택 (시나리오 보강)**
- 조치:
    - TS-11을 “DLQ + no processing side effects”로 명확히 강화
- 이유:
    - 계약 위반 메시지는 시스템 내부 상태에 흔적을 남기면 안 됨

---

### F8. D4(IdempotencyKey) 규칙 직접 검증 시나리오 누락
- 유형: Missing Scenario
- 정책 근거:
    - POLICY.md §Event Types
    - SESSION-01 D4
- 이슈 요약:
    - authErrorId-only idempotencyKey 규칙이
      EventDescriptor 레벨에서 직접 검증되지 않음.
- 결정: **🟨 보류**
- 이유:
    - 이미 Outbox 동작 시나리오(TS-03/TS-04)에서 간접적으로 강제됨
    - 단위 테스트 성격이 강해 현 단계에서는 중복으로 판단

---

## 3. 세션 결과 요약

### 채택(✅)
- F1 Decision Apply State Gate
- F2 Recorded Handler Skip (ANALYSIS_REQUESTED)
- F3 Analysis Request Atomicity
- F4 Outbox Publish Failure Handling
- F6 Payload Parse Failures → DLQ
- F7 Missing Headers → DLQ + No Side Effects

### 보류(🟨)
- F5 Retry Routing Ladder (broker-level)
- F8 IdempotencyKey Descriptor-level direct test

### 거절(❌)
- 없음

---

## 4. 다음 액션

1. `docs/TEST_SCENARIOS.md`에 **채택된 시나리오(F1, F2, F3, F4, F6, F7)** 반영
2. 보류 항목(F5, F8)은 정책/운영 이슈 발생 시 재검토
3. 이후 단계:
    - SESSION-03: Test Scenarios → Integration Test Mapping
    - 또는 핵심 불변식부터 통합 테스트 구현 착수
