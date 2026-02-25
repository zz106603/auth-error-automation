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
| baseline_E2E_p95 (ms) | **548.00** | `histogram_quantile(0.95, sum by (le) (rate(auth_error_e2e_seconds_bucket[1m])))` | 소비 완료 시점 기준 |
| baseline_E2E_p99 (ms) | **619.00** | `histogram_quantile(0.99, sum by (le) (rate(auth_error_e2e_seconds_bucket[1m])))` |  |
| baseline_outbox_age_p95 (ms) | **0.000** | `auth_error_outbox_age_p95` | backlog 없음(0) |
| baseline_outbox_age_p99 (ms) | **0.000** | `auth_error_outbox_age_p99` |  |
| baseline_ingest_rate (req/s) | **4.34** | `sum(rate(http_server_requests_seconds_count{uri="/api/auth-errors"}[1m]))` | HTTP RPS 기준 |
| baseline_publish_rate (msg/s) | **8.68** | `sum(rate(auth_error_publish_total{result="success"}[1m]))` | 앱 메트릭 기준 |
| baseline_consume_rate (ack/s) | **8.68** | `sum(rate(auth_error_consume_total{result="success"}[1m]))` | 앱 메트릭 기준 |
| baseline_retry_enqueue_rate (msg/s) | **0.000** | `auth_error.retry.enqueue` counter delta / 10s | baseline assumed none |
| baseline_dlq_rate (msg/s) | **0.000** | `auth_error.dlq` counter delta / 10s | baseline assumed none |
| baseline_publish_silence_ms | **0.000** | `clamp_min(time() * 1000 - max(auth_error_publish_last_success_epoch_ms), 0)` | publish 정지 감지 (작을수록 정상) |

---

## 3. RabbitMQ 상태 (STOP 조건 기반)

> 수집 경로: RabbitMQ Prometheus 플러그인(/metrics/detailed) 지표 사용

| Metric | Value | Source (PromQL) |
| --- | --- | --- |
| Ready | **0.000** | `sum by (queue) (rabbitmq_detailed_queue_messages_ready{queue!=""})` |
| Unacked | **0.000** | `sum by (queue) (rabbitmq_detailed_queue_messages_unacked{queue!=""})` |
| Publish rate (app) | **8.68** | `sum(rate(auth_error_publish_total{result="success"}[1m]))` |
| Deliver rate (app) | **8.68** | `sum(rate(auth_error_consume_total{result="success"}[1m]))` |
| Retry depth | **0.000** | `sum by (queue) (rabbitmq_detailed_queue_messages_ready{queue=~".*\\.retry\\..*"} + rabbitmq_detailed_queue_messages_unacked{queue=~".*\\.retry\\..*"})` |
| DLQ depth | **0.000** | `sum by (queue) (rabbitmq_detailed_queue_messages_ready{queue=~".*\\.dlq"} + rabbitmq_detailed_queue_messages_unacked{queue=~".*\\.dlq"})` |

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
- `publish_silence_ms` → `clamp_min(time() * 1000 - max(auth_error_publish_last_success_epoch_ms), 0)`

> 10초 단위 증가량으로 rate 계산 (PromQL: `increase(x[10s])/10`)

### 4.4 Hikari / Pool Saturation
- `connections.pending` → `hikaricp.connections.pending`
- `connections.active` → `hikaricp.connections.active`

### 4.5 MQ Health
- `Ready` → `rabbitmq_detailed_queue_messages_ready`
- `Unacked` → `rabbitmq_detailed_queue_messages_unacked`
- `publish_rate` → `rate(auth_error_publish_total{result="success"}[1m])`
- `deliver_rate` → `rate(auth_error_consume_total{result="success"}[1m])`
- `retry_depth` → `ready+unacked (queue=~".*\\.retry\\..*")`
- `DLQ depth` → `ready+unacked (queue=~".*\\.dlq")`

---

## 5. Notes
- E2E 측정은 recorded 이벤트에 한정.
- client-side `client_ingest_latency_ms`는 참고용이며 STOP 조건에는 사용하지 않는다.
