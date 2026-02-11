# Load Test Checklist (이해 중심 버전)

auth-error-automation — Single-Node Local

---

# 0. 이 테스트는 뭘 확인하는가?

이 시스템은 이런 흐름이다:

```
API → DB 저장 → Outbox → MQ → Consumer → Retry → DLQ
```

이 부하 테스트는 단순히 “요청을 많이 보내보기”가 아니다.

우리가 확인하려는 것은:

1. 파이프라인이 밀리지 않는가?
2. 밀릴 때 조용히 붕괴하지 않는가?
3. Retry/DLQ가 설계대로 동작하는가?

---

# 1. 가장 중요한 개념 3가지

## 1) E2E Latency (진짜 처리 시간)

“요청이 들어온 시점부터 최종 처리 완료까지 걸린 시간”

API가 빨리 응답해도 의미 없다.

Consumer까지 끝나야 진짜 성공이다.

👉 이게 3배 이상 늘어나면 위험 신호.

---

## 2) Backlog Age (대기열에 얼마나 오래 쌓였는가)

메시지가 몇 개 쌓였냐(count)가 아니라,

“가장 오래된 메시지가 몇 초째 묵어 있는가(age)”를 본다.

왜?

count는 잠깐 줄어들 수 있다.

age는 조용한 붕괴를 숨기지 못한다.

👉 age가 계속 증가하면 이미 밀리고 있는 상태.

---

## 3) Stage Throughput 균형

각 단계가 서로 따라가고 있는지 본다.

- ingest_rate (API 유입 속도)
- publish_rate (Outbox → MQ 발행 속도)
- consume_rate (Consumer 처리 속도)

👉 ingest > publish 이면 Outbox 병목

👉 publish > consume 이면 Consumer 병목

---

# 2. 테스트는 4단계다

## LT-001 Baseline

정상 상태에서:

- E2E p95
- Outbox age
- ingest/publish/consume rate

를 기록한다.

이 값이 이후 모든 판단의 기준이 된다.

---

## LT-002 Ramp-up

부하를 단계적으로 올린다.

목표:

- 어느 지점부터 밀리기 시작하는지 찾기
- 첫 병목이 어디인지 찾기

관찰:

- E2E가 갑자기 3배 이상 증가
- publish < ingest
- consume < publish

---

## LT-003 Steady Load

부하를 일정하게 유지한다 (10~20분).

목표:

- 처음엔 괜찮아 보이지만
- 시간이 지나면서 age가 계속 증가하는지 확인

이게 “조용한 붕괴” 탐지다.

---

## LT-004 Failure Injection

일부러 실패를 만든다.

확인:

- TTL ladder대로 재시도 되는가?
- max-retries 도달 시 DLQ로 가는가?
- malformed 메시지는 즉시 DLQ로 가는가?

---

# 3. Stop Conditions를 쉽게 이해하면

STOP은 “망했다”가 아니라,

👉 “지금은 정상 부하 테스트를 계속하면 안 되는 상태”

라는 의미다.

## E2E

- baseline 대비 3배 이상 증가하면 중단

## Backlog Age

- baseline 대비 3~5배 증가
- 10초마다 측정했을 때 계속 상승

## Throughput mismatch

- ingest > publish 가 60초 지속
- publish > consume 가 60초 지속

## Pool saturation

- connections.pending 존재 + active == maxPoolSize

---

# 4. 성공이란 무엇인가?

성공은 단 하나다:

> 파이프라인이 균형을 유지하며 처리량을 따라간다.
>

API가 빠른 건 성공이 아니다.

아래 4개가 모두 안정적이어야 성공이다:

- E2E 안정
- Backlog age 상승 없음
- Stage mismatch 없음
- Retry/DLQ 정책 정상 동작

---

# 5. 왜 이렇게 복잡하게 하는가?

이 시스템은 비동기 구조다.

API는 빠르게 응답할 수 있다.

하지만 뒤에서 Outbox/MQ/Consumer가 밀릴 수 있다.

그래서:

- API latency만 보면 안 되고
- backlog age를 봐야 하고
- publish/consume 균형을 봐야 한다.

---

# 한 줄 요약

이 부하 테스트는:

> “요청을 얼마나 빨리 받느냐”가 아니라
>
>
> “요청을 끝까지 제대로 처리하느냐”를 검증하는 테스트다.
>