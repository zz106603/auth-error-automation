# LT-002 Ramp-up 결과 (Baseline-Aware Threshold Discovery)

## 0. 실행 정보

- 실행 일시(LOCAL): 2026-02-27 15:33 ~ 15:58 (KST)
- Executor: ramping-arrival-rate
- Stage: 5 → 10 → 20 → 30 → 40 → 50 → 60 → 70 → 80 RPS
- 총 요청 수: 63,147
- 최대 VUs: 100
- 환경: Local single-node (Spring Boot + PostgreSQL 16 + RabbitMQ 3.13)

---

# 1. 테스트 목적 (재정의)

LT-002의 목적은:

- 단순 TPS 측정이 아니라
- Baseline(LT-001) 대비 **임계점(threshold)** 탐색
- Stage별로 Stop 조건 위반 여부 확인
- 파이프라인(outbox → publish → consume → DB) 정합성 검증
- 병목 발생 지점 식별

---

# 2. Baseline 기준값 (LT-001)

| Metric | Baseline |
| --- | --- |
| baseline_E2E_p95 | 548 ms |
| baseline_E2E_p99 | 619 ms |
| baseline_outbox_age_p95 | 0 ms |
| baseline_publish_rate | 8.68 msg/s |
| baseline_consume_rate | 8.68 msg/s |

Stop 조건 예시:

- E2E_p95 > baseline × 3 for 60s
- E2E_p99 > baseline × 5 for 60s
- publish > consume mismatch > 5% for 60s
- Hikari pending > 0 AND active == maxPool for 15s

---

# 3. Stage별 주요 관측 결과

## 3.1 70 RPS Stage

- E2E p95 ≈ baseline × ~2 수준
- 60초 이상 지속 위반 없음
- Hikari pending 지속 없음
- publish ≈ consume

→ Stop 조건 위반 없음

---

## 3.2 80 RPS Stage

### 3.2.1 E2E

- E2E p95 ≈ 20,000 ms
- baseline × 3 기준: 1,644 ms
- 실제: baseline 대비 약 36배
- 해당 상태가 약 60초 이상 지속

→ Stop 조건 (E2E multiplier rule) 충족

---

### 3.2.2 HTTP vs E2E

- HTTP p95 ≈ 70 ms
- E2E p95 ≈ 20 s

→ API는 빠르게 응답하나, 내부 파이프라인 지연 발생

---

### 3.2.3 Hikari

- connections.active == maxPoolSize 구간 존재
- connections.pending > 0 구간 존재
- 해당 구간이 E2E 급등 시점과 정렬됨

→ DB connection contention 강하게 시사

---

### 3.2.4 MQ / Throughput

- publish 증가
- consume 거의 따라감
- sustained publish > consume 60s 이상 증거 없음
- retry depth 증가 없음
- DLQ depth 0 유지

→ Consumer 병목은 “강하게 증명되지는 않음”

→ 구조적 붕괴 없음

---

# 4. HTTP 성공 ↔ DB 정합성 검증

| 항목 | 값 |
| --- | --- |
| Total HTTP Requests | 63,147 |
| Failed HTTP Requests | 53 |
| Successful HTTP Requests | 63,094 |
| auth_error rows | 63,094 |

명시적 정합성:

> Successful HTTP requests == auth_error rows
>

따라서 ingest 단계에서의 데이터 손실은 없음.

---

# 5. 파이프라인 정합성 검증

| Table | Count |
| --- | --- |
| auth_error | 63,094 |
| auth_error_analysis_result | 63,094 |
| auth_error_cluster_item | 63,094 |
| outbox_message | 126,188 |
| processed_message | 126,188 |

검증:

- analysis_result == auth_error
- cluster_item == auth_error
- outbox_message == 2 × auth_error
- processed_message == outbox_message

→ Outbox → Publish → Consume → DB 경로에서

**데이터 유실 및 중복 없음**

---

# 6. 임계점 판단

## Threshold 정의

Threshold는 다음 조건을 만족하는 최초 Stage로 정의:

- E2E_p95 baseline × 3 초과
- 60초 이상 지속
- 시스템 복구 없이 유지

## 판단

- 70 RPS: 조건 미충족
- 80 RPS: 조건 충족

따라서,

> 단일 노드 환경의 안정 상한은 70 RPS
>
>
> 80 RPS에서 임계점 도달
>

---

# 7. 병목 해석 (증거 기반)

강하게 지지되는 증거:

- Hikari pool saturation 신호
- E2E 급등과 시간 정렬

약하게 지지되는 가설:

- Consumer 처리 속도 병목

  (sustained publish>consume 증거는 부족)


따라서 최종 해석:

> Primary bottleneck hypothesis: DB connection pool contention
>
>
> Secondary hypothesis: downstream consumer throughput limitation (not conclusively proven)
>

---

# 8. 결론

### 구조적 신뢰성

- 메시지 유실 없음
- Retry/DLQ 정상
- Outbox 정합성 유지
- Silent collapse 없음

### 임계점 발견

- Baseline-aware Stop 조건 위반은 80 RPS에서 최초 발생
- Threshold ≈ 75~80 RPS 구간

---

# 9. 다음 단계 (가설 검증 실험)

1. Hikari maxPoolSize 16 → 32
2. Consumer concurrency 4 → 8
3. 동일 Ramp-up 재실행
4. Stage별 sustained window 재검증

---

# 핵심 요약 (정량 기반)

- 80 RPS에서 E2E baseline × 36배 초과
- 60초 이상 지속 위반
- DB pool saturation 신호 동반
- 파이프라인 정합성 유지

→ Capacity threshold confirmed under baseline-aware rules.