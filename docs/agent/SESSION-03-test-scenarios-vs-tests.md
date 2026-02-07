# SESSION-03 Test Scenarios vs Existing Tests Review

## 0. 목적
본 세션은 `docs/TEST_SCENARIOS.md`에 정의된 정책 기반 테스트 시나리오와
현재 `src/test/java` 하위에 존재하는 테스트 코드들을 비교하여,

- 어떤 시나리오가 실제로 검증되고 있는지
- 어떤 시나리오가 전혀 검증되지 않고 있는지
- 기존 테스트의 강점/한계를 명확히

하기 위해 수행되었다.

본 세션은 **코드 수정이나 테스트 추가를 즉시 요구하지 않으며**,
우선순위 판단을 위한 근거를 남기는 데 목적이 있다.

---

## 1. 전체 요약

### 시나리오 커버리지 현황

| 구분 | 개수 |
|----|----|
| TOTAL Test Scenarios | 13 |
| COVERED | 0 |
| PARTIAL | 1 |
| MISSING | 12 |

- 현재 테스트는 **파이프라인 중복 수신 시 멱등성 1건**만 부분적으로 검증하고 있음
- 핵심 정책(멱등성, 원자성, 상태 전이, retry/DLQ)은 대부분 **문서로만 존재**하고 테스트로는 강제되지 않음

---

## 2. 시나리오별 검토 결과

### TS-01 API 멱등성: requestId 기반 AuthError 중복 방지
- 상태: ❌ MISSING
- 현재 검증 내용:
    - requestId 중복에 대한 테스트 없음
- 누락된 핵심:
    - 동일 requestId로 여러 API 호출 시 auth_error 1건 보장
    - unique constraint 위반을 멱등 흐름으로 처리하는지 여부
- 위험:
    - 클라이언트 재시도 시 5xx 증가
    - 멱등성 보장이 코드 리팩토링 과정에서 쉽게 깨질 수 있음

---

### TS-02 원자성: AuthError 저장 + Recorded Outbox
- 상태: ❌ MISSING
- 현재 검증 내용:
    - 도메인 저장과 outbox enqueue의 트랜잭션 결합 검증 없음
- 누락된 핵심:
    - 실패 지점별(all-or-nothing) 상태 보장
- 위험:
    - auth_error만 남고 recorded 이벤트가 없는 “영구 정체 상태” 발생 가능

---

### TS-03 / TS-04 Outbox 멱등성 (recorded / analysis_requested)
- 상태: ❌ MISSING
- 현재 검증 내용:
    - idempotencyKey 형식/충돌 방지에 대한 테스트 없음
- 누락된 핵심:
    - authErrorId 기반 idempotencyKey 강제
- 위험:
    - requestId 재사용 시 서로 다른 AuthError 이벤트 드랍
    - 운영 환경에서 조용한 이벤트 유실

---

### TS-05 Outbox Claim 동시성 제어
- 상태: ❌ MISSING
- 현재 검증 내용:
    - 다중 poller 환경에서 단일 claim 보장 테스트 없음
- 누락된 핵심:
    - FOR UPDATE SKIP LOCKED 기반 단일 owner 확보
- 위험:
    - 중복 publish / 이중 처리 가능성

---

### TS-06 Outbox Finalize: owner 일치 조건
- 상태: ❌ MISSING
- 현재 검증 내용:
    - processing_owner 불일치 시 finalize 차단 테스트 없음
- 누락된 핵심:
    - owner mismatch에 대한 무효 처리
- 위험:
    - 잘못된 finalize로 상태 전이 오염 가능

---

### TS-07 Reaper Takeover
- 상태: ❌ MISSING
- 현재 검증 내용:
    - stale PROCESSING row takeover 테스트 없음
- 누락된 핵심:
    - takeover 조건과 이후 상태 전이의 합법성
- 위험:
    - PROCESSING 영구 정체 또는 잘못된 재큐잉

---

### TS-08 Consumer 멱등성: processed_message
- 상태: ⚠️ PARTIAL
- 매칭 테스트:
    - AuthErrorPipelineFailureIntegrationTest#파이프라인_중복_수신_시_멱등성_보장_확인
- 현재 검증 내용:
    - DONE 상태에서 중복 delivery 시 상태 유지
- 누락된 핵심:
    - outbox_id 기준 processed_message 단일 row 보장
    - 동시 ensureRowExists 경합 시 멱등성
- 위험:
    - processed_message 중복 생성 → exactly-once 가정 붕괴
- 관련 support:
    - DuplicateDeliveryInjector

---

### TS-09 Lease 만료 후 재claim
- 상태: ❌ MISSING
- 현재 검증 내용:
    - lease_until 만료/재claim 시나리오 없음
- 누락된 핵심:
    - lease 기반 배타 처리 경계
- 위험:
    - 동일 outbox 메시지 이중 처리

---

### TS-10 Retry 기준: next_retry_at
- 상태: ❌ MISSING
- 현재 검증 내용:
    - DB next_retry_at 기준 claim 차단 테스트 없음
- 누락된 핵심:
    - now < next_retry_at / >= 조건 분기
- 위험:
    - retry 타이밍 왜곡, 재시도 누락

---

### TS-11 Missing Header → DLQ
- 상태: ❌ MISSING
- 현재 검증 내용:
    - 계약 위반 메시지 즉시 DLQ 테스트 없음
- 누락된 핵심:
    - retry 없이 DLQ + 내부 상태 무변경
- 위험:
    - poison message가 시스템 내부 상태를 오염

---

### TS-12 Terminal 상태 skip
- 상태: ❌ MISSING
- 현재 검증 내용:
    - terminal 상태에서 out-of-order 이벤트 무시 테스트 없음
- 누락된 핵심:
    - 상태 불변성 보장
- 위험:
    - 이미 종료된 AuthError가 다시 변형됨

---

### TS-13 Cluster linking 멱등성
- 상태: ❌ MISSING
- 현재 검증 내용:
    - cluster_item 단일 링크 보장 테스트 없음
- 누락된 핵심:
    - 중복 analysis 결과에 대한 안전성
- 위험:
    - cluster count 부풀림, 분석 결과 왜곡

---

## 3. 핵심 결론

- 현재 테스트는 **정책을 “보호”하지 못하고 있음**
- 정책은 매우 정교하지만, 테스트는 그 의도를 거의 반영하지 않음
- 이는 “테스트가 부족하다”기보다  
  → **정책을 나중에 문서화했기 때문에 자연스러운 상태**

---

## 4. 다음 단계 제안 (선택)

### 옵션 A. 최소 안전망부터 구축
- TS-01 (API 멱등성)
- TS-02 (원자성)
- TS-03/04 (Outbox 멱등성)
- TS-08 (processed_message 멱등성 보강)

### 옵션 B. 메시징 안정성 중심
- TS-05 / TS-06 / TS-07
- TS-10 / TS-11

### 옵션 C. 운영/관측 중심
- TS-12 / TS-13

---

## 5. 세션 종료 판단
본 세션을 통해 **정책 ↔ 테스트 간 괴리**가 명확히 식별되었으며,
이후 테스트 추가/보강은 “문서 기반 선택”으로 진행 가능해졌다.

이 상태에서의 코드 수정은 **의도 없는 리팩토링**이 아니라,
명확한 정책 보강 작업이 된다.
