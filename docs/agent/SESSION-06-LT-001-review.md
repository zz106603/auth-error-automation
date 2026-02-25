# SESSION-06 — LT-001 Review (Baseline Readiness)

## 0. 결론 (Go / No-Go)

- **LT-002 Ramp-up: No-Go**
- 사유: LT-001 결과가 체크리스트에서 요구하는 baseline 지표를 충족하지 못하며, 다수 STOP 조건이 계산 불가 상태임.

---

## 1. LT-001에서 현재 가능한 관측

### 1.1 HTTP Latency 관측 가능

- `/actuator/metrics/http.server.requests`
- p95 / p99 확인 가능

→ API 레벨 응답 지연은 확인 가능

### 1.2 HikariCP Pool 상태 관측 가능

- `hikaricp.connections.active`
- `hikaricp.connections.pending`
- `hikaricp.connections.idle`

→ DB pool 포화 여부는 확인 가능

---

## 2. LT-001에서 누락된 핵심 지표

### 2.1 Server-side E2E latency 없음 (STOP 5.1 계산 불가)

현재:

- k6에서 client-side `Date.now - occurredAt` 방식

문제:

- pipeline completion을 측정하지 못함
- consumer 처리 완료 시점이 아님

필요:

- Consumer 성공 직전(ack/markDone 직전)에서

  `now - occurredAt` Timer 기록

- metric: `auth_error.e2e`
- p95 / p99 / max 필요

---

### 2.2 Outbox backlog age 없음 (STOP 5.2 계산 불가)

현재:

- outbox pending/processing row age percentile 측정 지표 없음

문제:

- silent collapse 탐지 불가
- 개수가 아니라 age(p95/p99)로 봐야 함

필요:

- percentile_disc 기반 p95/p99 계산
- 10초 주기 스케줄러
- metric: `auth_error.outbox.age`
- slope 계산 가능해야 함

---

### 2.3 Stage throughput 지표 없음 (STOP 5.3 계산 불가)

필요한 지표:

- ingest_rate (API 진입)
- publish_rate (Rabbit confirm 성공 기준)
- consume_rate (ack 기준)

현재:

- 관련 counter가 코드에 존재하지 않음

필요:

- `auth_error.ingest`
- `auth_error.publish`
- `auth_error.consume`
- per-10s delta로 rate 계산

---

### 2.4 Retry / DLQ 관측 불가

필요:

- retry enqueue rate
- retry attempt 분포 (1/2/3+)
- DLQ reason taxonomy
- last successful publish timestamp

현재:

- 전부 미구현

---

### 2.5 RabbitMQ broker depth 지표 없음 (STOP 5.5 계산 불가)

필요:

- ready
- unacked
- publish_rate
- deliver_rate
- retry_depth
- dlq_depth

현재:

- Actuator만으로는 제공되지 않음
- RabbitMQ Prometheus plugin(/metrics/detailed) 지표 사용

---

## 3. 우선순위 정리 (P0 중심)

### P0-1) Consumer에 Server-side E2E Timer 추가

- auth_error.e2e

### P0-2) Outbox age p95/p99 + slope 추가

- auth_error.outbox.age

### P0-3) ingest/publish/consume/retry/dlq 카운터 추가

- auth_error.ingest
- auth_error.publish
- auth_error.consume
- auth_error.retry.enqueue
- auth_error.dlq
- auth_error.publish.last_success_epoch_ms

### P0-4) RabbitMQ plugin 지표 기반 쿼리 추가

- rabbitmq_detailed_queue_messages_ready
- rabbitmq_detailed_queue_messages_unacked
- rate(auth_error_publish_total{result=success}[1m])
- rate(auth_error_consume_total{result=success}[1m])
- ready+unacked (queue=~".*\\.retry\\..*")
- ready+unacked (queue=~".*\\.dlq")

### P0-5) Actuator percentile histogram 설정

- auth_error.e2e p95/p99
- auth_error.outbox.age p95/p99

---

## 4. k6 및 문서 수정 포인트

### 4.1 k6의 “E2E” rename

- 기존 e2e_latency_ms → client_ingest_latency_ms

### 4.2 LT-001-baseline.md 구조 변경

- narrative 제거
- baseline 수치 테이블화
- STOP condition 매핑 섹션 추가

---

## 5. Ramp-up 시 가장 위험한 구간

1순위:

- Outbox poller + Rabbit publish(confirm-bound) 경로

2순위:

- API + Outbox + Consumer DB 동시 부하로 Hikari saturation

---

## 6. LT-001 재실행 계획 (Baseline 확보 목적)

1. k6 스크립트 수정 (E2E rename + 태그 추가)
2. /actuator/metrics에서 다음 수집:
   - auth_error.e2e p95/p99/max
   - auth_error.outbox.age p95/p99 + slope
   - auth_error.ingest
   - auth_error.publish
   - auth_error.consume
   - auth_error.retry.enqueue
   - auth_error.dlq
   - auth_error.publish.last_success_epoch_ms
   - Hikari metrics
   - RabbitMQ plugin metrics
3. 결과를 LT-001-baseline.md에 표 형태로 기록 (날짜/환경 포함)
