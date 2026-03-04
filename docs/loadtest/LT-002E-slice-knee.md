# LT-002E Knee Slice 결과 (Repeatable Knee Confirmation)

## 0. 실행 정보

- 실행 일시:
- Slice Plan:
- Clean Run 수:
- 환경:

---

# 1. 테스트 목적

Ramp-up에서 발견된 임계점 구간을
plateau 기반으로 반복 검증하여

- Safe upper bound 확정
- Knee range 확정
- Primary collapse signal 식별

---

# 2. Slice별 주요 관측 결과

## 80 RPS Hold

- E2E p95:
- Outbox age:
- publish vs consume:
- Hikari 상태:
- Drain 결과:

판단:

---

## 85 RPS Hold

(동일 구조 반복)

---

# 3. 반복성 검증

| Run | 80 Hold | 85 Hold | 90 Hold | Drain | Collapse Signal |
|------|---------|---------|---------|-------|----------------|

반복성 판단:

---

# 4. 최종 판단

Safe upper bound:

Knee range:

Primary collapse signal:

Confidence:

---

# 5. 병목 해석

- 1차 병목:
- 2차 가설:
- 정합성 검증 결과:

---

# 6. 구조적 신뢰성

- 메시지 유실 여부:
- Retry/DLQ 상태:
- Outbox 정합성:
- Silent collapse 여부:

---

# 핵심 요약

- 안정 상한:
- 임계 구간:
- 최초 붕괴 지표:
- 반복성 확보 여부:

---

## Run 2 – e1 (local,lt-e1)

### Run 목적

- DB pool 크기 변화만 knee 구간에 미치는 영향을 확인한다.
- e1은 Hikari max pool만 `16 -> 32`로 변경한다.
- Consumer concurrency는 `4`로 유지된다.
- Retry ladder, outbox poller, metrics, topology 설정은 그대로 유지된다.

### 실행 명령

```powershell
$env:SPRING_PROFILES_ACTIVE="local,lt-e1"
./gradlew bootRun
```

```powershell
pwsh -File k6/run-lt-002-slice-knee.ps1
```

### Run 메타데이터

- `test_id`: `LT-002E-2026-03-03_155658`
- `generated_at`: `2026-03-03T07:15:23.326Z`
- `duration_ms`: `1051287.892698`

### Slice Schedule

| Slice | Target RPS | Duration | Phase |
|------|------------:|---------:|-------|
| 0 | 60 | 120s | hold |
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
| 11 | 0 | 30s | cooldown |
| 12 | 0 | 180s | cooldown |

### k6 Summary

```text
test_id=LT-002E-2026-03-03_155658
generated_at=2026-03-03T07:15:23.326Z
duration_ms=1051287.892698
iterations=79465
http_reqs=79465
http_req_duration_avg=56.48309806446838
http_req_duration_p95=111.69629280000008
http_req_duration_max=6210.792703
http_req_failed_rate=0.002693009501038193
check_fail_rate=0.002693009501038193
```

### PromQL Snapshots

```text
max_over_time(auth_error_e2e_seconds_max[5m]) = 834.599
sum(rate(auth_error_publish_total{result="success"}[1m])) = 85.00481022308544
sum(rate(auth_error_consume_total{result="success"}[1m])) = 82.48788544256702
```

### 해석

#### Observed behavior

- 80-90 RPS 구간에서 run 자체는 유지되었지만, publish/consume 사이에 작은 차이가 지속되었다.
- HTTP p95는 `111.696ms`로 낮게 유지되었으나, max는 `6210.793ms`까지 관측되었다.

#### What improved / did not improve

- DB pool 상한을 `32`로 늘렸음에도 publish와 consume이 완전히 수렴하지는 않았다.
- 즉, DB pool 확장만으로 knee 구간의 모든 병목이 제거되었다고 보기는 어렵다.

#### Evidence of long-tail E2E

- `max_over_time(auth_error_e2e_seconds_max[5m]) = 834.599s`는 매우 긴 tail이 존재함을 보여준다.
- 이 값은 steady-state 대표 latency라기보다 retry 개입 또는 장기 체류 샘플 존재를 시사한다.

#### Publish vs consume delta

- `publish_rate ~= 85.00 rps`
- `consume_rate ~= 82.49 rps`
- 소비가 생산을 약 `2.52 rps` 하회하므로 backlog 누적 위험이 남아 있다.

#### What remains unknown

- 이 run만으로 이전 suspicious behavior의 1차 원인이 DB pool이었는지 확정할 수는 없다.
- consumer concurrency를 올리지 않은 상태이므로, consumer-side ceiling이 남아 있는지 아직 분리되지 않았다.

#### Why e2 run is needed next

- e2는 DB pool이 아니라 consumer concurrency를 올리는 실험이다.
- 따라서 e1과 e2를 비교해야 DB-side 제약 완화 이후에도 consumer-side 병목이 남는지 확인할 수 있다.
- e2 결과를 함께 봐야 실제 knee 이동 여부와 primary bottleneck 위치를 더 명확히 판단할 수 있다.
