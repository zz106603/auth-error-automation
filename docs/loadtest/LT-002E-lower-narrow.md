# LT-002E Lower-Narrow 결과 (Repeatable Knee Confirmation)

## 0. 실행 정보

- 테스트 시나리오: LT-002E Lower-Narrow Knee Slice
- 목적: Ramp-up에서 발견된 임계 구간을 lower-narrow plateau 방식으로 반복 검증
- 환경: Local single-node
- 대상 Run ID
  - LT-002E-2026-03-11_200155
  - LT-002E-2026-03-11_202954
  - LT-002E-2026-03-11_205243
- 실행 프로파일: local

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
| 0 | 50 | 120s | hold |
| 1 | 60 | 30s | ramp |
| 2 | 60 | 150s | hold |
| 3 | 65 | 30s | ramp |
| 4 | 65 | 150s | hold |
| 5 | 70 | 30s | ramp |
| 6 | 70 | 150s | hold |
| 7 | 75 | 30s | ramp |
| 8 | 75 | 150s | hold |
| 9 | 0 | 30s | cooldown |
| 10 | 0 | 180s | cooldown |

---

# 1. 테스트 목적

Ramp-up 테스트(LT-002)에서 발견된 임계 구간을 plateau 기반으로 반복 검증하여 다음을 확인한다.

- Safe upper bound 확정
- Knee range 확정
- Primary collapse signal 식별
- DB / MQ / Consumer 중 실제 병목 위치 확인

---

# 2. Slice별 주요 관측 결과

## 50 RPS Hold

- ingest->consume p95/p99 sustained fail 관측
- Outbox age: 증가 추세 없음
- Publish vs Consume
  - publish_rate와 consume_rate는 대체로 수렴
- Rabbit 상태
  - Ready ≈ 0
  - Unacked ≈ 0~3
  - Retry depth ≈ 0
  - DLQ depth ≈ 0
- Hikari 상태
  - maxPoolSize=16
  - pending은 run별 편차 존재
- Drain 결과
  - 테스트 종료 후 queue backlog 없음
  - retry depth 안정
- 판단
  - HTTP/MQ 관점에서는 안정
  - ingest->consume 장지연 신호는 존재

---

## 60~65 RPS Hold

- ingest->consume p95/p99 sustained fail 지속
- Outbox age: steady
- Publish vs Consume: sustained mismatch 없음
- Rabbit 상태
  - ready ≈ 0
  - unacked ≈ 0~3
  - retry depth ≈ 0
- 판단
  - MQ backlog/retry 관점에서는 안정
  - collapse signal은 ingest->consume latency에서 반복 관측

---

## 70~75 RPS Hold

- ingest->consume p95/p99 sustained fail 지속
- HTTP error rate는 낮은 수준 유지
- Rabbit 상태
  - ready ≈ 0
  - unacked ≈ 0~3
  - retry depth ≈ 0
- 판단
  - 구조적 붕괴는 없지만
  - lower-narrow 범위에서도 latency signal은 해소되지 않음

---

# 3. 반복성 검증

| Run | 50 Hold | 60~65 Hold | 70~75 Hold | Drain | Collapse Signal |
| --- | --- | --- | --- | --- | --- |
| LT-002E-2026-03-11_200155 | latency fail | latency fail | latency fail | ok | ingest->consume |
| LT-002E-2026-03-11_202954 | latency fail | latency fail | latency fail | ok | ingest->consume |
| LT-002E-2026-03-11_205243 | latency fail | latency fail | latency fail | ok | ingest->consume |

반복성 판단: 50~75 RPS 구간에서 ingest->consume 장지연 신호가 반복 관측됨

---

# 4. 최종 판단

- Safe upper bound: 미확정
- Knee range: lower-narrow 결과만으로 재정의 보류
- Primary collapse signal: ingest->consume latency sustained fail
- Confidence: medium

---

# 5. 병목 해석

- 1차 병목: DB write path (connection pool / insert workload)
- 근거
  - MQ backlog / retry / DLQ 증가 없음
  - publish와 consume의 sustained mismatch 없음
- 2차 가설: outbox insert + cluster linking 비용
- 정합성 검증 결과
  - 이번 문서는 local lower-narrow 3회만 포함
  - consumer concurrency 부족 문제는 아직 lt-e2 lower-narrow 재실행 전이므로 확정 불가

---

# 6. 구조적 신뢰성

- 메시지 유실 여부: 관측되지 않음
- Retry / DLQ 상태
  - Retry depth 안정
  - DLQ 없음
- Outbox 정합성: Backlog 증가 없음
- Silent collapse 여부: 관측되지 않음
- queue/reset contamination: pre-run clean 및 post-run drain 기준으로 강한 오염 증거 없음

---

# 핵심 요약

- 안정 상한: 미확정
- 임계 구간: lower-narrow 범위에서 재검증 필요
- 최초 붕괴 지표: ingest->consume sustained fail
- 반복성 확보 여부: local 3회 run 기준 동일 신호 반복 확인

---

# Run 기록

## Run 1 – LT-002E-2026-03-11_200155

- 변경 사항
  - local profile
  - SliceProfile=lower-narrow
- 결과 요약
  - ingest->consume p95/p99 sustained fail
  - drain ok
- 해석
  - lower-narrow 첫 run에서도 동일 latency signal 확인
  - MQ/retry 붕괴 신호는 없음

---

## Run 2 – LT-002E-2026-03-11_202954

- 변경 사항
  - local profile
  - SliceProfile=lower-narrow
- 결과 요약
  - ingest->consume p95/p99 sustained fail
  - hikari saturation fail 1회 관측
  - drain ok
- 해석
  - 동일 latency signal이 재현됨
  - 다만 hikari saturation은 run 간 일관되게 재현되지는 않음

---

## Run 3 – LT-002E-2026-03-11_205243

- 변경 사항
  - local profile
  - SliceProfile=lower-narrow
- 결과 요약
  - ingest->consume p95/p99 sustained fail
  - drain ok
- 해석
  - 앞선 두 run과 같은 방향의 latency signal 재현
  - HTTP/MQ 쪽은 상대적으로 안정

---

# 결론

- 이번 LT-002E lower-narrow 테스트 결과
  - **local profile 3회에서 동일한 ingest->consume latency signal이 반복 관측됨**
  - **MQ backlog / retry 폭주 / DLQ 증가는 관측되지 않음**
  - **lt-e1 / lt-e2는 아직 이 문서 범위에 포함되지 않음**

따라서 현재 구조에서의 **LT-002 결론은 아직 닫지 않고 lower-narrow 범위에서 추가 검증**이 필요하다.

다음 단계는 **LT-E1 / LT-E2를 동일한 lower-narrow profile로 재실행하여 원인 분리**를 진행하는 것이다.

권장 테스트
- LT-E1 with lower-narrow
- LT-E2 with lower-narrow

확인 지표
- ingest->consume latency 변화
- hikari saturation 재현성
- publish/consume 균형 및 drain 상태
