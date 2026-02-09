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

### TS-02 원자성: AuthError 저장과 Recorded Outbox는 함께 커밋된다
- 상태: ❌ MISSING
- 현재 검증 내용:
    - 도메인 저장과 outbox enqueue의 트랜잭션 결합 검증 없음
- 누락된 핵심:
    - 실패 지점별(all-or-nothing) 상태 보장
- 위험:
    - auth_error만 남고 recorded 이벤트가 없는 “영구 정체 상태” 발생 가능

---

### TS-03 Outbox 멱등성: recorded 이벤트는 authErrorId 기준으로 1회만 생성된다
- 상태: ❌ MISSING
- 현재 검증 내용:
    - idempotencyKey 형식/충돌 방지에 대한 테스트 없음
- 누락된 핵심:
    - authErrorId 기반 idempotencyKey 강제
- 위험:
    - requestId 재사용 시 서로 다른 AuthError 이벤트 드랍
    - 운영 환경에서 조용한 이벤트 유실

---

### TS-04 Outbox 멱등성: analysis_requested 이벤트도 authErrorId 기준으로 1회만 생성된다
- 상태: ❌ MISSING
- 현재 검증 내용:
    - analysis_requested 이벤트의 중복 생성 방지 테스트 없음
- 누락된 핵심:
    - authErrorId 기반 idempotencyKey 강제
- 위험:
    - 분석 요청 중복 발생으로 인한 리소스 낭비 및 데이터 정합성 문제

---

### TS-05 Recorded Handler 동작 제한: ANALYSIS_REQUESTED 상태에서는 재요청하지 않는다
- 상태: ❌ MISSING
- 현재 검증 내용:
    - 이미 진행된 상태에서의 중복 recorded 이벤트 처리 방지 테스트 없음
- 누락된 핵심:
    - 상태 기반의 처리 skip 로직
- 위험:
    - 불필요한 재처리 및 상태 롤백/오염 가능성

---

### TS-06 Decision 적용 제한: ANALYSIS_COMPLETED 상태에서만 허용된다
- 상태: ❌ MISSING
- 현재 검증 내용:
    - 올바르지 않은 상태에서의 Decision 적용 차단 테스트 없음
- 누락된 핵심:
    - 상태 전이의 전제 조건 검증
- 위험:
    - 분석이 완료되지 않은 상태에서 잘못된 의사결정 적용

---

### TS-07 Analysis 요청의 원자성: 상태 전이와 Outbox 생성은 함께 이뤄진다
- 상태: ❌ MISSING
- 현재 검증 내용:
    - 상태 변경과 이벤트 발행의 트랜잭션 원자성 테스트 없음
- 누락된 핵심:
    - 상태만 변경되고 이벤트가 발행되지 않는 불일치 방지
- 위험:
    - 시스템 상태와 실제 이벤트 흐름 간의 괴리 발생

---

### TS-08 Outbox publish 실패 처리: retry와 dead 분기
- 상태: ❌ MISSING
- 현재 검증 내용:
    - publish 예외 종류에 따른 분기 처리 테스트 없음
- 누락된 핵심:
    - retry 가능/불가능 예외 구분 및 DEAD 처리
- 위험:
    - 일시적 오류로 인한 메시지 유실 또는 영구적 오류의 무한 재시도

---

### TS-09 Consumer 멱등성: processed_message는 outbox_id 기준 1건만 존재한다
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

### TS-10 Retry 기준: DB next_retry_at 단일 기준
- 상태: ❌ MISSING
- 현재 검증 내용:
    - DB next_retry_at 기준 claim 차단 테스트 없음
- 누락된 핵심:
    - now < next_retry_at / >= 조건 분기
- 위험:
    - retry 타이밍 왜곡, 재시도 누락

---

### TS-11 계약 위반 메시지: missing header는 즉시 DLQ + 무부작용
- 상태: ❌ MISSING
- 현재 검증 내용:
    - 계약 위반 메시지 즉시 DLQ 테스트 없음
- 누락된 핵심:
    - retry 없이 DLQ + 내부 상태 무변경
- 위험:
    - poison message가 시스템 내부 상태를 오염

---

### TS-12 Payload 파싱 실패: poison message는 즉시 DLQ
- 상태: ❌ MISSING
- 현재 검증 내용:
    - 파싱 불가 메시지에 대한 DLQ 처리 테스트 없음
- 누락된 핵심:
    - 비즈니스 로직 진입 전 차단 및 DLQ 이동
- 위험:
    - 파싱 오류로 인한 컨슈머 무한 재시도 루프

---

### TS-13 Terminal 상태 보호: terminal AuthError는 재처리되지 않는다
- 상태: ❌ MISSING
- 현재 검증 내용:
    - terminal 상태에서 out-of-order 이벤트 무시 테스트 없음
- 누락된 핵심:
    - 상태 불변성 보장
- 위험:
    - 이미 종료된 AuthError가 다시 변형됨

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
- TS-09 (processed_message 멱등성 보강)

### 옵션 B. 메시징 안정성 중심
- TS-08 (Publish 실패 처리)
- TS-10 / TS-11 / TS-12 (Retry 및 DLQ)

### 옵션 C. 운영/관측 중심
- TS-05 / TS-06 / TS-07 (상태 전이 및 원자성)
- TS-13 (Terminal 보호)

---

## 5. 세션 종료 판단
본 세션을 통해 **정책 ↔ 테스트 간 괴리**가 명확히 식별되었으며,
이후 테스트 추가/보강은 “문서 기반 선택”으로 진행 가능해졌다.

이 상태에서의 코드 수정은 **의도 없는 리팩토링**이 아니라,
명확한 정책 보강 작업이 된다.
