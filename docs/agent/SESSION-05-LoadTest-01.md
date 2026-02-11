# SESSION-05 · LoadTest-01 (Codex Review 반영) — Single-Node Local, Production-grade

## 1) Context
- 프로젝트: auth-error-automation
- 아키텍처: Spring Boot + PostgreSQL + RabbitMQ + Transactional Outbox
- 파이프라인:
    - API → DB insert → Outbox → Publisher → RabbitMQ → Consumer → Retry(TTL) → DLQ → Domain status
- 목표:
    - k6 자체가 목적이 아니라, **파이프라인 붕괴(조용한 적체)** 를 “숫자”로 잡는 **관측/판단 체계**를 고정한다.
- 환경 가정:
    - Local single-node 실행 (multi-node/HA/Failover는 범위 밖)

---

## 2) Inputs
- 기준 문서: `docs/LOAD_TEST_CHECKLIST.md` (v1)
- Codex 출력 요지(핵심):
    1) Observability signals 부족 (publish/consumer/retry/dlq/latency/lock 등)
    2) 성공 기준이 잘못될 수 있음(Outbox 0 회복, MQ stable, DLQ 0 등은 “거짓 성공” 가능)
    3) 실패 시나리오 누락(Outbox poller stall, redelivery storm, TTL misconfig 등)
    4) Stop condition 정량화 부족(“지속 증가/비정상” 같은 표현)
    5) 개선안 제시(E2E latency, backlog age, per-stage throughput & counters, DB contention, duplicate detection)

---

## 3) Decisions (채택/기각/보류)

### 3.1 채택 (Must Adopt)
#### A) End-to-End Latency (E2E) 추적 추가
- 이유:
    - API가 빠르게 응답해도 async buffering으로 파이프라인이 붕괴할 수 있음.
- 정의(로컬 최소):
    - E2E = `now - occurredAt` (또는 `now - recordedAt`)로 **프로젝트 기준을 하나로 고정**
- 산출 방식(로컬 단일):
    - DB query 기반 집계(p95/p99/max) 또는 로그 기반 측정 중 1개를 최소 구현

#### B) Backlog “Count”가 아니라 “Age” 지표 추가
- 이유:
    - “조용한 붕괴”는 count보다 age가 먼저 올라감.
- 최소 필수:
    - Outbox oldest age = `now - min(created_at where status=PENDING)`
    - (가능 시) Retry oldest age
- Count와 함께 보고, **age 상승 추세**를 최우선 붕괴 신호로 취급

#### C) Per-stage Throughput / Failure Counters 최소 세트 도입
- 이유:
    - Outbox가 줄어도 publish drop/실패로 줄었을 수 있음(거짓 성공 방지)
- 최소 카운터(단일 노드 기준):
    - Publish success/fail counter
    - Consumer handled success/fail counter
    - Retry attempt 분포(최소: attempt=1/2/3+)
    - DLQ count + reason code taxonomy(최소: PARSE / DOWNSTREAM / TIMEOUT / UNKNOWN)
    - Last successful publish timestamp(또는 최근 성공 시간)

#### D) Stop Conditions “정량화(시간창+임계치)”로 전환
- 이유:
    - “지속 증가/비정상”은 판단 불가, 재현 불가
- 원칙:
    - 모든 stop condition은 `(threshold) for (window)` 형태로 작성
    - Baseline 대비 악화폭(Δ)도 함께 고려

---

### 3.2 보류 (Phase 2/3에서 점진 도입)
#### A) Duplicate delivery counter / Requeue rate
- 이유:
    - RabbitMQ connection flap/redelivery storm 시나리오에서 유효
    - 로컬 단일 “정상 부하” Phase에서는 우선순위 낮음
- 처리:
    - Failure Injection 단계(LT-004)에서 필요 시만 추가

#### B) DB WAL latency / commit rate / deadlock count
- 이유:
    - EC2/운영급에서는 의미가 크지만, 로컬 단일에서는 비용 대비 효용이 낮을 수 있음
- 처리:
    - 로컬에서는 `pg_locks/pg_stat_activity + slow query`를 1차로 사용
    - 추후 필요 시 확장

---

### 3.3 기각 (현재 범위 밖)
#### A) Multi-node deployment 가정 기반 요구사항
- 이유:
    - 현재 테스트 목표는 “단일 노드에서 파이프라인 건강도 수치화”
    - 분산/HA/Failover는 추후 별도 phase에서 다룬다

---

## 4) Actions (문서/구현 변경 작업)

### 4.1 문서 변경
- `docs/LOAD_TEST_CHECKLIST.md`를 v2로 개정한다.
    - E2E latency 섹션 추가
    - Backlog age 지표 추가
    - Stage throughput/counters 추가
    - Stop conditions 정량화(시간창+임계치)
    - “거짓 성공” 방지 체크 추가

### 4.2 최소 구현(관측)
- Actuator/Micrometer로 기본 지표 노출 유지
- 아래 항목 중 “로컬 최소” 방식으로 추가(둘 중 택1)
    1) **DB query 기반** E2E p95/p99 산출 쿼리 작성 + 결과 기록
    2) **애플리케이션 메트릭**으로 E2E 관측(가능하면)

- Publisher/Consumer/Retry/DLQ counters:
    - 로그 기반이라도 1차 확보 가능(단, 최종적으로는 metric 권장)
    - DLQ reason taxonomy 최소 4종으로 시작

---

## 5) Risk Notes (Codex가 지적한 실패 패턴 반영)

### 5.1 “거짓 성공” 위험
- Outbox backlog 0 회복:
    - publish가 실패/드랍되어도 줄어들 수 있음 → publish success/fail + last success time로 방지
- MQ depth stable:
    - processing latency 상승/Unacked 증가로 붕괴 가능 → Unacked + ack/처리 시간 추세 확인
- DLQ 증가 없음:
    - endless retry loop 가능 → retry attempt 분포 + retry depth/age 확인

### 5.2 누락되기 쉬운 장애 시나리오(Phase 3에서 다룰 것)
- outbox poller stall(스케줄링 정지/스레드 죽음)
- consumer ack 실패로 redelivery storm
- retry TTL misconfig로 immediate bursts 또는 과도한 지연
- downstream backpressure로 retry 증폭(Queue amplification)
- payload spike(대형 stacktrace)로 메모리 압박/메시지 거부

---

## 6) Proposed Stop Conditions (초안, 로컬 단일 기준)

> 실제 수치는 Baseline 확보 후 조정한다.  
> 형태는 반드시 “임계치 + 시간창”으로 고정한다.

### DB/Pool
- Hikari pending > 0 for 30s
- 5xx rate ≥ 1% over 60s
- slow query(>=1000ms) ≥ 10/min for 2min

### Outbox/Publisher
- outbox PENDING oldest age > 60s for 60s
- outbox PENDING count increasing for 120s (단조 증가 추세)
- publish fail ≥ 1/min for 2min OR last publish success timestamp stale > 60s

### MQ/Consumer
- main queue Ready increasing for 120s AND deliver rate < publish rate 지속
- Unacked > (consumer_concurrency * 2) for 60s
- 처리 실패율(consumer fail) 상승 추세 + retry depth 증가 동반

### Retry/DLQ
- retry depth increasing for 120s
- DLQ rate > ingest * 0.5% over 60s (의도적 실패 시나리오 제외)
- attempt=3+ 비율 상승 + DLQ 전환 없음(정책 미스 의심)

### System caps
- heap 사용량 상승 추세 + GC pause 상승(정량 기준은 Baseline 기반)
- CPU > 90% for 60s (OS 툴로 확인)

---

## 7) Next Step (즉시 실행 순서)

1) `docs/LOAD_TEST_CHECKLIST.md` v2 반영(문서 먼저)
2) Baseline(LT-001) 수행 준비:
    - E2E 측정 방식(쿼리 or metric) 확정
    - Outbox oldest age 쿼리 준비
    - publish/consume/retry/dlq counters 확보
3) k6 시나리오 설계:
    - LT-001 Baseline → LT-002 Ramp-up → LT-003 Steady → LT-004 Failure Injection

---

## 8) Done Criteria (이번 세션 완료 조건)
- [ ] LOAD_TEST_CHECKLIST v2에 Codex 지적사항(핵심 4개)이 반영됨
    - E2E latency
    - backlog age
    - per-stage counters/throughput
    - 정량 stop conditions
- [ ] Baseline 실행에 필요한 최소 쿼리/관측 경로가 준비됨
- [ ] 다음 세션에서 바로 k6 스크립트로 진입 가능

---

## 9) Stop Conditions v2.1 (Baseline-aware, Single-Node Local)

> 원칙:
> - 절대값(예: 60s/2min) 단독 사용 금지 → Baseline 배수 + 추세(slope) + mismatch 로직으로 보강
> - “가능한 지표”와 “추가 구현이 필요한 지표”를 분리
> - Failure Injection(LT-004)에서는 일부 조건(예: attempt=3+ 상승)을 Stop이 아니라 “기대 현상”으로 취급

### 9.1 Baseline 정의 (LT-001에서 산출)
- baseline_E2E_p95
- baseline_E2E_p99
- baseline_outbox_age_p95
- baseline_outbox_age_p99
- baseline_ingest_rate (req/s)
- baseline_publish_rate (msg/s)
- baseline_consume_rate (ack/s)

---

### 9.2 즉시 적용 가능한 Stop Rules (추가 구현 없이 적용 가능)

#### A) E2E Latency (가장 중요)
- STOP if `E2E_p95 > baseline_E2E_p95 * 3` for 60s (LT-002~003)
- STOP if `E2E_p99 > baseline_E2E_p99 * 5` for 60s (LT-002~003)
- STOP if `E2E_max > baseline_E2E_p95 * 10` occurs ≥ 2 times within 60s (spike 탐지)

> 이유: “stable” 같은 표현 대신, baseline 대비 악화폭을 정량화

#### B) Outbox Backlog Age (Count보다 우선)
- STOP if `outbox_age_p95 > baseline_outbox_age_p95 * 3` for 60s
- STOP if `outbox_age_p99 > baseline_outbox_age_p99 * 5` for 30s
- STOP if `outbox_age_slope > +1s per 10s` for 3 consecutive windows (아래 계산 참고)

slope 계산(운영 규칙):
- 10초 창마다 outbox_age_p95를 찍고,
- (현재 - 10초전) > +1s 가 3번 연속이면 STOP

> 이유: oldest age는 가려질 수 있음. p95/p99 + slope가 조용한 붕괴에 강함.

#### C) Stage Throughput Mismatch (명시적 로직)
- STOP if `(ingest_rate - publish_rate) / ingest_rate > 5%` for 60s
- STOP if `(publish_rate - consume_rate) / publish_rate > 5%` for 60s

추가 규칙(Non-failure runs: LT-001~003):
- STOP if `retry_enqueue_rate / consume_rate > 10%` for 60s

> 이유: “측정은 하는데 감지를 못 하는” 문제 방지.
> 특히 retry 증폭(숨은 amplification)을 잡기 위한 조건.

#### D) HikariCP / API (오탐 줄이기)
기존: pending > 0 for 30s  → 오탐 가능
개선: 아래 조합으로 STOP

- STOP if `pending > 0 for 15s` AND `active == maxPoolSize` (pool saturation)
- STOP if `HTTP 5xx rate >= 0.2%` for 60s (auth 성격 고려, 1%는 너무 느슨)
    - 단, 로컬에서 5xx가 너무 희귀하면 “5xx count >= N”으로 보조 가능

> 이유: 1개 느린 쿼리로 pending이 생기는 오탐을 줄이고,
> 진짜 saturation(활성=최대)일 때만 멈춤.

#### E) RabbitMQ Unacked (prefetch 고려)
기존: `Unacked > concurrency*2` → prefetch 설정에 따라 항상 발동 가능(오탐)
개선:
- STOP if `Unacked / (consumer_count * prefetch)` > 0.8 for 60s
- 또는(로컬 단순 버전) STOP if `Unacked`가 60초 동안 **단조 증가** AND `consume_rate` 하락 동반

> 전제: prefetch 값을 기록해야 함.

---

### 9.3 “추가 구현 필요” Stop Rules (옵션: v3에서)

아래는 지표가 있으면 강력하지만, 현재 시스템에 없다면 ‘추가 구현’로 남긴다.

- STOP if `connection_wait_p95 > 50ms` for 60s (DB connection wait histogram 필요)
- STOP if `Unacked_age_p95 > 10s` for 60s (message age 측정 필요)
- STOP if `Ready_age_p95 > 10s` for 60s (queue message age 측정 필요)

---

### 9.4 Failure Injection (LT-004) 전용 처리
- attempt=3+ 비율 상승은 “기대 현상”일 수 있음 → 즉시 STOP 금지
- 대신 아래만 STOP:
  - DLQ로 가야 하는데 안 가는 경우(정책 미스)
  - retry가 즉시 폭주(즉시 재시도)하며 TTL ladder가 무시되는 경우
  - DLQ reason taxonomy가 UNKNOWN 일색인 경우(분류 실패)
