# LT-001 Baseline 결과 (Template)

## 0. 실행 정보
- 실행 일시(UTC): YYYY-MM-DDTHH:mm:ssZ
- 실행 일시(LOCAL): YYYY-MM-DD HH:mm:ss (TZ)
- k6 실행 환경: Docker k6 → Local Spring
- DB/MQ: Docker (PostgreSQL, RabbitMQ)
- Git commit: <hash>

---

## 1. k6 실행 설정
- Executor: constant-arrival-rate
- Rate: 5 requests/sec
- Duration: 3 minutes
- 총 요청 수: <N>
- VUs: preAllocated 20 / max 50

---

## 2. Baseline Metrics (필수)

| Metric | Value | Source | Notes |
| --- | --- | --- | --- |
| baseline_E2E_p95 (ms) | **817.55** | `/actuator/metrics/auth_error.e2e?tag=event_type:auth.error.recorded.v1` | 소비 완료 시점 기준 |
| baseline_E2E_p99 (ms) | **1417.25** | `/actuator/metrics/auth_error.e2e?tag=event_type:auth.error.recorded.v1` |  |
| baseline_outbox_age_p95 (ms) | **0.000** | `/actuator/metrics/auth_error.outbox.age.p95` | PENDING/PROCESSING 포함 |
| baseline_outbox_age_p99 (ms) | **0.000** | `/actuator/metrics/auth_error.outbox.age.p99` |  |
| baseline_ingest_rate (req/s) | **4.873** | `auth_error.ingest` counter delta / 10s | `tag: api=/api/auth-errors` |
| baseline_publish_rate (msg/s) | **4.873** | `auth_error.publish` counter delta / 10s | `tag: result=success` |
| baseline_consume_rate (ack/s) | **4.873** | `auth_error.consume` counter delta / 10s | `tag: result=success` |
| baseline_retry_enqueue_rate (msg/s) | **0.000** | `auth_error.retry.enqueue` counter delta / 10s | baseline assumed none |
| baseline_dlq_rate (msg/s) | **0.000** | `auth_error.dlq` counter delta / 10s | baseline assumed none |
| baseline_last_publish_success_epoch_ms | **1771921492091.000** | `/actuator/metrics/auth_error.publish.last_success_epoch_ms` | publish 정지 감지 |

---

## 3. RabbitMQ 상태 (STOP 조건 기반)

| Metric | Value | Source |
| --- | --- | --- |
| Ready | **0.000** | `/actuator/metrics/auth_error.rabbit.ready` |
| Unacked | **0.000** | `/actuator/metrics/auth_error.rabbit.unacked` |
| Publish rate | **5.000** | `/actuator/metrics/auth_error.rabbit.publish_rate` |
| Deliver rate | **5.000** | `/actuator/metrics/auth_error.rabbit.deliver_rate` |
| Retry depth | **0.000** | `/actuator/metrics/auth_error.rabbit.retry_depth` |
| DLQ depth | **0.000** | `/actuator/metrics/auth_error.rabbit.dlq_depth` |

---

## 4. STOP Condition Mapping (Enforceability)

### 4.1 E2E Latency
- `E2E_p95` → `auth_error.e2e` p95
- `E2E_p99` → `auth_error.e2e` p99
- `E2E_max` → `auth_error.e2e` max  
Note: E2E는 **AuthErrorRecordedConsumer 완료 시점 기준**으로 측정. Analysis consumer는 제외됨.

### 4.2 Outbox Backlog Age
- `outbox_age_p95` → `auth_error.outbox.age.p95`
- `outbox_age_p99` → `auth_error.outbox.age.p99`
- `outbox_age_slope` → `auth_error.outbox.age.slope_ms_per_10s`

### 4.3 Stage Throughput
- `ingest_rate` → `auth_error.ingest` counter delta / 10s
- `publish_rate` → `auth_error.publish{result=success}` delta / 10s
- `consume_rate` → `auth_error.consume{result=success}` delta / 10s
- `retry_enqueue_rate` → `auth_error.retry.enqueue` delta / 10s

> 10초 단위 증가량으로 rate 계산 (PromQL: `increase(x[10s])/10`)

### 4.4 Hikari / Pool Saturation
- `connections.pending` → `hikaricp.connections.pending`
- `connections.active` → `hikaricp.connections.active`

### 4.5 MQ Health
- `Ready` → `auth_error.rabbit.ready`
- `Unacked` → `auth_error.rabbit.unacked`
- `publish_rate` → `auth_error.rabbit.publish_rate`
- `deliver_rate` → `auth_error.rabbit.deliver_rate`
- `retry_depth` → `auth_error.rabbit.retry_depth`
- `DLQ depth` → `auth_error.rabbit.dlq_depth`

---

## 5. Notes
- E2E 측정은 recorded 이벤트에 한정.
- client-side `client_ingest_latency_ms`는 참고용이며 STOP 조건에는 사용하지 않는다.
