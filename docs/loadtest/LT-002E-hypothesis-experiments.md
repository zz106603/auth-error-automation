# LT-002E Knee Slice 설계 문서

## 0. 목적

LT-002E는 LT-002 Ramp-up에서 탐색된 임계점 구간을
정밀하게 재검증하기 위한 plateau 기반 Knee Slice 테스트이다.

목표는 다음 세 가지를 반복 가능한 증거로 확정하는 것이다.

- Safe upper bound
- Knee range
- Primary collapse signal

Ramp-up은 탐색(exploration) 단계이며,
LT-002E는 검증(confirmation) 단계이다.

---

# 1. Slice 계획

각 LT-002E 실행은 동일한 slice plan을 사용한다.

| Slice | Target RPS | Duration | 역할 |
|--------|------------|----------|------|
| 0 | 60 | 120s | 초기 안정 구간 |
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
| 11 | 0 | 30s | ramp-down |
| 12 | 0 | 180s | drain |

### 설계 원칙

- 150초 hold는 60초 지속 Stop 조건을 충분히 검증하기 위함
- 30초 ramp는 transition shock 완화 목적
- 180초 drain은 retry TTL(60s) 및 backlog 정리 확인 목적
- 90초 hold는 사용하지 않음 (Stop window 대비 부족)

---

# 2. 사전 Clean Gate 규칙

k6 실행 전 다음 조건이 60초 연속 유지되어야 한다.

- Rabbit Ready == 0
- Rabbit Unacked == 0
- retry depth == 0
- DLQ depth == 0
- outbox_age_p95 == baseline
- outbox_age_p99 == baseline
- hikaricp_connections_pending == 0

Clean 상태가 아니면 해당 run은 시작하지 않는다.

---

# 3. 사후 Drain 검증

k6 종료 후 다음 조건이 timeout 내에 baseline으로 복귀해야 한다.

- Ready == 0
- Unacked == 0
- retry depth == 0
- DLQ depth == 0
- outbox_age baseline 복귀
- hikaricp pending == 0

복귀 실패 시 run은 `contaminated` 처리한다.

---

# 4. 반복 전략

기본:

- 동일 설정으로 5회 clean run

시간 제약 시:

- 최소 3회 clean run

규칙:

- pre-run clean 실패 → run 무효
- drain 실패 → clean run으로 인정하지 않음
- Knee 판단은 clean run만 비교

---

# 5. 필수 수집 메트릭

- http.server.requests
- auth_error.e2e p95/p99/max
- auth_error_outbox_age_p95
- auth_error_outbox_age_p99
- auth_error_outbox_age_slope_ms_per_10s
- auth_error_publish_total
- auth_error_consume_total
- auth_error_retry_enqueue_total
- hikaricp_connections_active
- hikaricp_connections_pending
- rabbitmq_ready
- rabbitmq_unacked
- retry queue depth
- DLQ depth

---

# 6. Stop 조건

Hold 구간에서만 평가한다.

- E2E_p95 > baseline × 3 for 60s
- E2E_p99 > baseline × 5 for 60s
- outbox_age_p95 > baseline × 3 for 60s
- outbox_age_slope > +1s/10s 3회 연속
- publish > consume 5% 초과 60s
- retry_enqueue_rate > 10%
- pending > 0 AND active == maxPool
- Ready 단조 증가 60s
- Unacked saturation 60s