# LT-002E Knee Slice 결과 (Repeatable Knee Confirmation)

## 0. 실행 정보

- 테스트 시나리오: LT-002E Knee Slice
- 목적: Ramp-up에서 발견된 임계 구간을 plateau 방식으로 반복 검증
- 환경: Local single-node

### 관련 문서

- lower-narrow 후속 검증 결과: `docs/loadtest/LT-002E-lower-narrow.md`

### 애플리케이션 실행

- Spring Boot (local profile)

### 인프라

- PostgreSQL (Docker)
- RabbitMQ (Docker)

### 부하 테스트 도구

- k6 (docker run)

### Slice Plan

| Slice | Target RPS | Duration | Phase |
| --- | --- | --- | --- |
| 0 | 60 | 120s | hold |
| 1 | 70 | 30s | ramp |
| 2 | 70 | 150s | hold |
| 3 | 75 | 30s | ramp |
| 4 | 75 | 150s | hold |
| 5 | 80 | 30s | ramp |
| 6 | 80 | 150s | hold |
| 7 | 85 | 30s | ramp |
| 8 | 85 | 150s | hold |
| 9 | 90 | 30s | ramp |
| 10 | 90 | 150s | hold |
| 11 | 0 | 30s | cooldown |
| 12 | 0 | 180s | cooldown |

---

# 1. 테스트 목적

Ramp-up 테스트(LT-002)에서 발견된 임계 구간을 plateau 기반으로 반복 검증하여 다음을 확인한다.

- Safe upper bound 확정
- Knee range 확정
- Primary collapse signal 식별
- DB / MQ / Consumer 중 실제 병목 위치 확인

---

# 2. Slice별 주요 관측 결과

## 80 RPS Hold

- E2E p95: 약 100~110ms
- Outbox age: 증가 추세 없음
- Publish vs Consume
  - publish_rate ≈ 48 rps
  - consume_rate ≈ 75 rps
- Rabbit 상태
  - Ready ≈ 0
  - Unacked ≈ 0
  - Retry depth ≈ 0
  - DLQ depth ≈ 0
- Hikari 상태
  - active < maxPoolSize
  - pending ≈ 0
- Drain 결과
  - 테스트 종료 후 queue backlog 없음
  - retry depth 안정
- 판단
  - Pipeline stable
  - Backpressure 없음

---

## 85 RPS Hold

- E2E p95: 약 105~115ms
- Outbox age: steady
- Publish vs Consume: consume_rate > publish_rate
- Rabbit 상태
  - ready ≈ 0
  - unacked ≈ 0
  - retry depth ≈ 0
- 판단
  - 시스템 안정 상태 유지
  - collapse signal 없음

---

## 90 RPS Hold

- E2E p95: 약 110ms
- Observed anomaly: 간헐적인 connection refused 발생
- 하지만: http_req_failed_rate ≈ 0.02%
- Rabbit 상태
  - ready ≈ 0
  - unacked ≈ 0
  - retry depth ≈ 0
- 판단
  - 일시적인 connection 오류는 있었지만
  - 지속적인 시스템 붕괴는 발생하지 않음

---

# 3. 반복성 검증

| Run | 80 Hold | 85 Hold | 90 Hold | Drain | Collapse Signal |
| --- | --- | --- | --- | --- | --- |
| e1 | stable | slight publish/consume gap | stable | ok | none |
| e2 | stable | stable | stable | ok | none |

반복성 판단: 80~90 RPS 구간에서 시스템 안정 동작 반복 확인

---

# 4. 최종 판단

- Safe upper bound: 85 RPS
- Knee range: 85~95 RPS
- Primary collapse signal: 명확한 collapse signal 관측되지 않음
- Confidence: medium-high

---

# 5. 병목 해석

- 1차 병목: DB write path (connection pool / insert workload)
- 근거
  - consumer throughput > publish throughput
  - 즉 MQ consumer는 병목이 아님
- 2차 가설: outbox insert + cluster linking 비용
- 정합성 검증 결과
  - consumer capacity가 충분하므로
  - consumer concurrency 부족 문제는 아님

---

# 6. 구조적 신뢰성

- 메시지 유실 여부: 관측되지 않음
- Retry / DLQ 상태
  - Retry depth 안정
  - DLQ 없음
- Outbox 정합성: Backlog 증가 없음
- Silent collapse 여부: 관측되지 않음

---

# 핵심 요약

- 안정 상한: 85 RPS
- 임계 구간: 85~95 RPS
- 최초 붕괴 지표: 관측되지 않음
- 반복성 확보 여부: 2회 run 기준 안정 동작 확인

---

# Run 기록

## Run 1 – e1 (DB Pool 증가)

- 변경 사항
  - Hikari maxPoolSize
  - 16 → 32
- 결과 요약
  - publish_rate ≈ 85 rps
  - consume_rate ≈ 82 rps
- 해석
  - DB pool 확장은 publish throughput에 일부 영향을 주었지만
  - consumer와 완전히 수렴하지는 않음
- E2E max: 약 834 sec
- retry lifecycle에 의한 long-tail latency로 판단된다.

---

## Run 2 – e2 (Consumer concurrency 증가)

- 변경 사항: consumer concurrency 증가
- 결과 요약
  - publish_rate ≈ 48 rps
  - consume_rate ≈ 75 rps
- 해석
  - consumer throughput이 충분하므로
  - consumer는 병목이 아님
- E2E max: 약 538 sec
- retry lifecycle에 의한 tail latency로 해석된다.

---

# 결론

- 이번 LT-002E slice 테스트 결과
  - 시스템은 **85 RPS까지 안정적으로 동작**
  - **MQ backlog / retry 폭주 / DLQ 증가 없음**
  - **consumer는 병목이 아님**

따라서 현재 구조에서의 **실질적인 safe upper bound는 약 85 RPS**로 판단된다.

다음 단계는 **LT-003 Steady Load 테스트**이다.

권장 테스트
- 85 RPS
- 15~20분 유지

확인 지표
- E2E latency slope
- Outbox age slope
- retry accumulation 여부
