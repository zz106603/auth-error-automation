# Load Test Checklist (k6) — auth-error-automation (v2, Single-Node Local)

> Scope: API → DB insert → Outbox → Publisher → RabbitMQ → Consumer → Retry(TTL) → DLQ → Domain status  
> Goal: 로컬 단일 환경에서도 “API는 멀쩡한데 파이프라인이 조용히 무너지는” 상황을 **E2E latency + backlog age + stage throughput**으로 잡는다.

---

## 0. Core Principles (Non-Negotiable)

- ✅ “API p95”만으로 성공/실패 판단 금지. (async buffering 때문에 붕괴를 못 봄)
- ✅ 성공 기준은 **E2E latency가 안정적이고 backlog age가 통제**되는 것.
- ✅ Stop Conditions는 **정량**이어야 한다. (“지속 증가”, “비정상적” 금지)
- ✅ 모든 테스트는 Test ID(LT-xxx) + Git hash + 환경값을 기록한다.

---

## 1. Environment Record (필수 기록)

### 1.1 Runtime
- OS / Kernel:
- CPU / Memory:
- Java:
- Spring Boot:
- PostgreSQL:
- RabbitMQ:
- Docker/Compose(사용 시):
- 실행 방식: Local single-node (IDE / jar / docker)

### 1.2 핵심 설정(값 기록)
- HikariCP: `maximumPoolSize`, `connectionTimeout`, `maxLifetime`
- Outbox poller: interval / batch size / lock 방식(SKIP LOCKED 등)
- Publisher: publish batch / retry(있다면) / 실패 처리 방식
- Consumer: concurrency / prefetch / ack mode
- Retry TTL ladder: (예: 10s/30s/2m/…)
- DLQ 조건: max attempts / fatal errors / parsing failures

---

## 2. Observability Minimum Setup (Go/No-Go)

> 아래가 준비되지 않으면 부하 테스트 시작하지 않는다.

### 2.1 Actuator / Metrics
- [ ] `/actuator/health` OK
- [ ] `/actuator/metrics` 접근 가능
- [ ] (선택) `/actuator/prometheus` 노출

필수 메트릭(최소):
- HTTP: `http.server.requests` (count, p95/p99)
- JVM: `jvm.memory.used`, `jvm.gc.pause`
- Hikari:
    - `hikaricp.connections.active`
    - `hikaricp.connections.pending`
    - `hikaricp.connections.idle`

### 2.2 RabbitMQ Management
- [ ] UI 접근 가능 (`:15672`)
- [ ] Queue별 depth / rate 확인 가능

필수 관측:
- Ready, Unacked
- Publish rate, Deliver rate
- Retry queue depth
- DLQ depth

### 2.3 PostgreSQL 관측
- [ ] slow query 로그(기준: 500ms 또는 1000ms)
- [ ] `pg_stat_activity`, `pg_locks` 조회 가능

### 2.4 Domain / Pipeline Counters (v2 핵심)
> “성공/실패/정체”를 숫자로 판단하기 위해 최소 카운터를 잡는다.

- [ ] Outbox publish success/fail count (누적)
- [ ] Consumer handled success/fail count (누적)
- [ ] Retry publish count (누적) + attempt 분포(최소: attempt=1/2/3+)
- [ ] DLQ count (누적) + reason code taxonomy(최소: PARSE / DOWNSTREAM / TIMEOUT / UNKNOWN)

---

## 3. Required Metrics (v2 추가: E2E Latency + Backlog Age)

### 3.1 End-to-End Latency (E2E)
목표: “API 수집”이 아니라 “최종 처리”까지의 지연을 본다.

- 정의(예시):
    - `E2E = now - occurredAt` 또는 `now - recordedAt` (네 도메인 기준으로 고정)
- 측정 방법(로컬 최소):
    - DB query로 “처리 완료 row의 latency” 집계 (p95/p99)
    - 또는 로그/메트릭에 `auth_error_id`, `occurredAt` 기반으로 측정

권장 지표:
- E2E p95 / p99
- E2E max
- E2E가 시간에 따라 증가하는지(추세)

### 3.2 Backlog Age (Count가 아니라 Age)
“몇 개 쌓였냐”보다 “얼마나 오래 쌓였냐”가 붕괴 신호다.

필수(최소 2개):
- Outbox oldest age: `now - min(created_at where status=PENDING)`
- Main queue message age(p95): RabbitMQ에서 age 직접이 어렵다면 **DB 기반 대체 지표**로 추정
    - (대체) “outbox pending age” + “consumer 처리율”로 판단

추가(가능하면):
- Retry queue oldest age
- DLQ message age

---

## 4. Baseline (필수)

- [ ] 단일 요청 1회 API latency 기록
- [ ] 1~5 RPS, 2분 실행하여 아래 baseline 확보:
    - API p95
    - Hikari pending=0 유지 여부
    - Outbox pending count / oldest age가 0 또는 안정 상태로 회복
    - MQ depth가 0 또는 안정 상태로 회복
    - E2E p95 (처리 완료 기준)

---

## 5. Stop Conditions (정량, Single-Node Local 기준)

> Baseline 대비 “악화 폭”도 같이 본다.

### 5.1 DB / Pool
- [ ] `hikaricp.connections.pending` > 0 이 **30초 이상 지속**
- [ ] API timeout/5xx가 **1% 이상**(1분 창)
- [ ] slow query(>=1000ms)가 **분당 10건 초과**(로컬 기준)

### 5.2 Outbox / Publisher
- [ ] Outbox `PENDING count`가 **2분 연속 증가 추세** (증가가 멈추지 않음)
- [ ] Outbox `oldest age` > **60초**가 **60초 이상 지속**
- [ ] Publish fail count가 **분당 1건 초과** + 자동 회복 안 됨

### 5.3 MQ / Consumer
- [ ] Main queue Ready가 **2분 연속 증가** + Deliver rate가 따라가지 못함
- [ ] Unacked가 **(consumer concurrency * 2)** 를 초과하고 60초 유지
- [ ] Requeue/Redelivered 징후가 증가(가능하면) + 처리율 저하 동반

### 5.4 Retry / DLQ
- [ ] Retry queue depth가 **지속 증가(2분)** 하며 회복되지 않음
- [ ] DLQ rate가 **ingest의 0.5% 초과**(의도적 실패 시나리오 제외)
- [ ] “의도치 않은 endless retry” 징후:
    - attempt=3+ 비율이 계속 상승 + DLQ로 전환되지 않음

### 5.5 System Safety Caps
- [ ] JVM heap 사용량이 **지속적으로 증가**하며 GC pause가 증가 추세
- [ ] CPU 90%+가 **1분 이상 지속** (가능하면 OS 툴로 확인)

---

## 6. Scenarios (v2: Throughput + E2E + Age 중심)

### LT-001 Baseline Smoke (정상)
- 부하: 1~5 RPS, 2~5분
- 성공:
    - E2E p95 안정
    - Outbox oldest age가 0~작은 값으로 회복
    - DLQ 증가 0
- 수집:
    - API p95, E2E p95/p99, outbox pending count, outbox oldest age, queue depth

### LT-002 Ramp-up (임계점 찾기)
- 부하: 10 → 50 → 100 → 200 VUs (각 1~2분)
- 성공:
    - 임계점까지 E2E/age가 통제됨
    - 임계점에서 “어느 stage가 먼저 무너지는지” 식별됨
- 수집:
    - stage throughput(ingest vs publish vs consume), E2E 추세, oldest age 추세

### LT-003 Steady Load (지속)
- 부하: 100~200 VUs, 10~20분
- 성공:
    - 시간이 지날수록 E2E p95가 악화되지 않음
    - oldest age가 상승 추세가 아님
- 수집:
    - JVM GC pause, heap, outbox age, retry age(가능 시), DLQ 증가율

### LT-004 Failure Injection (의도적 실패)
- 전제: “실패 주입”을 명확히 켠다 (consumer failAlways 등)
- 성공:
    - retry TTL이 기대한 지연과 대략 일치
    - max attempts 후 DLQ로 이동
    - DLQ reason taxonomy가 의미 있게 남음

---

## 7. Results Template (필수)

### Execution
- Test ID:
- Date/Time (Asia/Seoul):
- Git hash:
- Env summary:
- k6 script / options:

### Numbers
- API p95/p99:
- E2E p95/p99/max:
- Hikari pending max:
- Outbox PENDING max:
- Outbox oldest age max:
- MQ main queue Ready max:
- Unacked max:
- Retry depth max:
- DLQ count delta:
- Publish fail/min:
- Consumer fail/min:
- Slow query/min:

### Interpretation
- First bottleneck stage:
- Evidence (metrics/logs):
- Next change (config/code) & re-run plan:

---

## 8. Go / No-Go Final Gate

- [ ] E2E latency 측정 경로 확정(쿼리/메트릭/로그 중 하나)
- [ ] Outbox oldest age 산출 가능
- [ ] Publish/Consume/Retry/DLQ 최소 카운터 존재
- [ ] Stop Conditions가 숫자로 적용 가능
- [ ] LT-001~LT-004 실행 계획 있음

> All checked → Proceed to k6 scripts.
