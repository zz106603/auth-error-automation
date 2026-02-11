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
