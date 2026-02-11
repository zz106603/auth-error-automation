# Load Test Checklist (k6)
auth-error-automation — Single-Node Local, Production-Grade Strategy

---

## 0. 목적 (What We Are Actually Testing)

이 부하 테스트의 목적은 단순 TPS 측정이 아니다.

목표는:

- ✅ 조용한 파이프라인 붕괴(silent collapse) 탐지
- ✅ Retry / DLQ 정책 검증
- ✅ Backpressure 감지 로직 검증
- ✅ Outbox 신뢰성 보장 검증

API latency만 빠른 상태는 성공이 아니다.

성공 기준은:

> E2E latency + Backlog Age + Stage Throughput 균형이 유지되는 것

---

# 1. 테스트 환경 기록

## 1.1 Runtime Environment

- OS / Kernel:
    - (테스트 실행 시 `uname -a`로 기록)

- CPU / Memory:
    - (테스트 실행 시 `lscpu`, `free -h`로 기록)

- Java:
    - 21

- Spring Boot:
    - 3.5.9

- PostgreSQL:
    - 16 (Docker)

- RabbitMQ:
    - 3.13-management (Docker)

- 실행 방식:
    - 애플리케이션은 로컬 단일 노드 실행
    - PostgreSQL / RabbitMQ는 Docker Compose 기반
    - (IDE 실행 또는 jar 실행 여부는 테스트 시 명시)

---

### 1.2 핵심 설정
- HikariCP:
    - maximumPoolSize: 16
    - minimumIdle: 8
    - connectionTimeout: 2000ms
- Outbox poller:
    - interval: 200ms
    - batch size: 100
- Consumer (Spring Rabbit simple):
    - concurrency: 4 (fixed)
    - prefetch: 25
    - ack mode: manual
    - defaultRequeueRejected: false
- Retry TTL ladder:
    - 1~2: 5s
    - 3~4: 30s
    - 5+: 60s
    - DB retry gate (`outbox.retry.delay-seconds`): 5s (must be <= shortest TTL)
- DLQ 조건:
    - Non-retryable 예외: 즉시 DLQ
    - Retryable 예외: max-retries(6) 도달 시 DLQ
    - Header/payload contract violation: reject(requeue=false) -> DLQ

---

### DLQ 정책

- 모든 메인 큐는 DLX 연결 구조
- 비정상 메시지(헤더/페이로드 오류)는 즉시 DLQ
- Decision = DEAD 시 DLQ 전송
- Non-retryable 예외 또는 maxRetries 초과 시 DLQ
- maxRetries 기본값: 6


---

# 2. Observability Minimum Setup (Go / No-Go)

## 2.1 필수 메트릭

- 사전 조건: `/actuator/metrics` 노출이 활성화되어 있어야 하며, HTTP/JVM/Hikari/Rabbit 지표를 동일 수집 주기로 기록한다.

### HTTP
- http.server.requests (p95 / p99)

### JVM
- heap usage
- GC pause

### Hikari
- connections.active
- connections.pending
- connections.idle

### RabbitMQ
- Ready
- Unacked
- Publish rate
- Deliver rate
- Retry depth
- DLQ depth

### Domain Counters (필수)
- Publish success / fail count
- Consumer success / fail count
- Retry attempt distribution (1 / 2 / 3+)
- DLQ reason codes
- Last successful publish timestamp

---

# 3. Baseline 정의 (LT-001에서 측정)

Baseline은 모든 Stop 조건의 기준이다.

반드시 기록:

- baseline_E2E_p95
- baseline_E2E_p99
- baseline_outbox_age_p95
- baseline_outbox_age_p99
- baseline_ingest_rate
- baseline_publish_rate
- baseline_consume_rate

Baseline 없이 절대값 기준만 사용하는 것은 금지.

---

# 4. 핵심 지표 정의

## 4.1 End-to-End Latency (E2E)

정의:
E2E = now - occurredAt (또는 recordedAt)

관측:
- p95
- p99
- max

E2E는 API latency보다 중요하다.

---

## 4.2 Backlog Age (Count보다 중요)

필수:

- outbox_age_p95
- outbox_age_p99
- outbox_age_slope

slope 계산:
10초 간격으로 p95를 측정하여 증가폭 계산

---

## 4.3 Stage Throughput

- ingest_rate
- publish_rate
- consume_rate
- retry_enqueue_rate

---

# 5. Stop Conditions (Baseline-Aware)

모든 조건은 "임계치 + 시간창" 구조를 따른다.

---

## 5.1 E2E Latency

STOP if:

- E2E_p95 > baseline_E2E_p95 * 3 for 60s
- E2E_p99 > baseline_E2E_p99 * 5 for 60s
- E2E_max > baseline_E2E_p95 * 10 twice within 60s

---

## 5.2 Outbox Backlog Age

STOP if:

- outbox_age_p95 > baseline_outbox_age_p95 * 3 for 60s
- outbox_age_p99 > baseline_outbox_age_p99 * 5 for 30s
- outbox_age_slope > +1s per 10s for 3 consecutive windows

---

## 5.3 Throughput Mismatch

STOP if:

'분모가 0보다 클 때만 계산'
- (ingest - publish) / ingest > 5% for 60s
- (publish - consume) / publish > 5% for 60s
- retry_enqueue_rate / consume_rate > 10% for 60s (non-failure runs)

---

## 5.4 Hikari / Pool Saturation

STOP if:

- connections.pending > 0 for 15s AND active == maxPoolSize
- HTTP 5xx rate >= 0.2% for 60s

---

## 5.5 MQ Health

STOP if:

- `publish_rate > consume_rate` 가 60s 이상 지속되고 `Ready`가 단조 증가
- `Unacked / (consumer_count * prefetch)` > 0.8 이 60s 이상 지속
- retry_queue depth(전체) 증가 + consume_rate 하락이 60s 이상 동시 발생 (Non-failure runs)

---

# 6. Failure Injection (LT-004 전용)

다음은 즉시 STOP 조건이 아니다:

- attempt=3+ 비율 상승
- Retry depth 상승

STOP 조건:

- TTL ladder가 적용되지 않고 즉시 재시도 폭주
- DLQ로 가야 할 메시지가 계속 retry 중
- DLQ reason taxonomy가 의미 없는 값만 남는 경우

---

# 7. 테스트 시나리오

## LT-001 Baseline
- 1~5 RPS
- 2~5분
- 모든 baseline 값 확보

## LT-002 Ramp-up
- 단계적 증가
- 임계점 탐색
- 어느 stage가 먼저 무너지는지 기록

## LT-003 Steady Load
- 10~20분 지속
- age 상승 추세 확인

## LT-004 Failure Injection
- consumer fail
- retry/dlq 정책 검증

---

# 8. 성공 정의

성공은 다음을 모두 만족할 때:

- E2E latency가 baseline 대비 안정적
- Backlog age가 상승 추세 아님
- Stage throughput mismatch 없음
- Retry/DLQ가 정책대로 동작
- System saturation 없음

API p95만 정상인 상태는 성공이 아니다.

---

# 9. 금지 사항

- 절대값 60s / 2min 같은 단독 기준 사용 금지
- "지속 증가" 같은 모호한 표현 사용 금지
- API latency만 보고 성공 판단 금지
