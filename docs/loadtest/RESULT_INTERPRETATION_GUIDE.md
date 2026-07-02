# Load Test Result Interpretation Guide

이 문서는 LT 실행 산출물을 운영 관점에서 해석하는 기준이다. 목적은 API latency만 보고 성공으로 오판하거나, scrape 누락 같은 관측 문제를 실제 성능 실패와 혼동하지 않도록 하는 것이다.

최종 수치는 Grafana 수동 조회가 아니라 `docs/loadtest/results/<test-id>/prometheus-snapshot.json`을 기준으로 판단한다. Markdown summary는 사람이 읽기 위한 요약이고, 원인 분석 시에는 snapshot JSON과 wrapper artifact를 함께 본다.

## 1. 먼저 볼 파일

표준 실행이 완료되면 아래 순서로 확인한다.

1. `docs/loadtest/results/<test-id>/prometheus-snapshot.json`
2. `docs/loadtest/results/<test-id>/<test-id>-summary.md`
3. `k6/results/<test-id>/wrapper-state.json`
4. `k6/results/<test-id>/pre_run_gate.summary.json`
5. `k6/results/<test-id>/post_run_drain.summary.json`
6. 실패 시 `FAILED-WRAPPER.txt`, `wrapper.failure.json`, `capture-and-report.failure.json`

`prometheus-snapshot.json`의 주요 필드:

| Field | 의미 |
| --- | --- |
| `metadata` | test id, scenario, run window, git sha, runtime settings, baseline path |
| `verdict` | `PASS`, `FAIL`, `UNKNOWN` 최종 판정 |
| `checks` | acceptance rule별 판정과 상세 사유 |
| `kpis` | E2E latency, Outbox age, queue depth, throughput, retry/DLQ 등 핵심 수치 |
| `anomalies` | counter reset, no-data, 이상 신호 등 보조 경고 |
| `appendix.queries` | snapshot 생성에 사용된 PromQL |

## 2. 판정 순서

운영 판단은 아래 순서를 따른다.

```text
wrapper failure 여부
-> snapshot verdict
-> UNKNOWN 원인 분리
-> E2E latency
-> Outbox backlog age
-> throughput mismatch
-> RabbitMQ queue depth
-> retry / DLQ
-> drain result
```

API p95만 정상이어도 성공으로 보지 않는다. Consumer까지 처리 완료되는 E2E와 테스트 종료 후 drain 여부가 더 중요하다.

## 3. PASS / FAIL / UNKNOWN 의미

| Verdict | 의미 | 후속 조치 |
| --- | --- | --- |
| `PASS` | 필수 snapshot query, baseline-relative check, queue/drain 조건이 통과했다. | 결과를 채택하되 `anomalies`와 summary 경고를 확인한다. LT-001이면 baseline 갱신 후보가 될 수 있다. |
| `FAIL` | 성능/안정성 조건 또는 실행 품질 조건이 실패했다. | 실패 check를 기준으로 병목 위치를 좁힌다. HTTP 2xx가 많아도 drain 실패나 post-run counter coverage 실패면 실패다. |
| `UNKNOWN` | 판정 근거가 부족하다. baseline 부재, scrape 누락, 샘플 부족, counter reset, artifact 누락 등이 대표 원인이다. | 성공으로 채택하지 않는다. 관측/산출물 문제를 먼저 복구한 뒤 재실행한다. |

`UNKNOWN`은 "문제 없음"이 아니라 "판단 불가"다.

## 4. Wrapper 실패 해석

| Failure | 의미 | 해석 절차 |
| --- | --- | --- |
| `clean_start_failed` | 테스트 전 시스템이 깨끗한 상태가 아니어서 k6를 시작하지 않았다. | pre-run gate summary에서 Outbox backlog, Rabbit ready/unacked, retry depth, DLQ depth, Hikari pending 중 무엇이 남았는지 본다. 이전 실행 오염이면 reset/drain 후 재실행한다. |
| `k6_failed` | k6 프로세스 실패, marker 누락, 부하 생성기 문제 가능성. | k6 stdout/log와 wrapper failure를 먼저 본다. 애플리케이션 성능 실패로 단정하지 않는다. |
| `drain_failed` | k6 종료 후 backlog가 제한 시간 안에 배출되지 않았다. | post-run drain samples에서 Outbox, ready/unacked, retry, DLQ 중 남은 위치를 본다. API 성공률과 무관하게 실패로 본다. |
| `snapshot_or_report_failed` | Prometheus snapshot 또는 report 생성 실패. | k6 실행 결과가 있어도 판정 근거가 없으므로 UNKNOWN에 가깝다. Prometheus 접근, query no-data, JSON 생성 실패를 확인한다. |
| `reset_state_failed` | 실행 전 강제 초기화 실패. | 테스트 환경 문제로 분류한다. 삭제/초기화 범위와 DB/RabbitMQ 상태를 확인한다. |

## 5. 관측 문제와 실제 실패 구분

| 현상 | 분류 | 기준 |
| --- | --- | --- |
| scrape 누락 | 관측 문제 | `critical_snapshot_queries_present=UNKNOWN`, `up{job=...}` no-data, 특정 KPI가 비어 있음 |
| baseline 부재 | 판정 근거 부족 | baseline-relative check가 `baseline file missing` 또는 `missing baseline p95/p99` |
| 샘플 부족 | 판정 근거 부족 | hold window가 짧거나 query range에 충분한 point가 없음 |
| counter reset | 관측/실행 품질 문제 | counter delta가 음수이거나 `anomalies`에 reset 계열 경고가 있음 |
| post-run counter coverage 실패 | 실행 품질 실패 | k6 요청 수 대비 publish/consume/timer delta가 부족함. LT-001 baseline으로 채택하지 않는다. |
| E2E sustained fail | 실제 처리 지연 가능성 | baseline 대비 p95/p99 초과가 threshold window 이상 지속 |
| Outbox age 증가 | 실제 backlog 가능성 | `outbox_age_slope` 또는 backlog count가 지속 증가 |
| publish/consume mismatch | stage 병목 가능성 | ingest > publish 또는 publish > consume이 지속 |
| retry/DLQ 증가 | 실패 처리 압력 | retry enqueue/depth, DLQ rate/depth, reason code를 함께 본다. |

관측 문제가 있으면 성능 결론을 내리지 않는다. 먼저 scrape, baseline, sample, artifact를 복구한다.

## 6. 운영 판독 흐름

### 6.1 E2E latency

먼저 `ingest_to_consume_p95_seconds`, `ingest_to_consume_p99_seconds`, `ingest_to_consume_max_seconds`를 본다. HTTP latency보다 우선한다. E2E가 baseline 대비 지속적으로 악화되면 API layer보다 뒤쪽 파이프라인을 먼저 의심한다.

### 6.2 Backlog age

`outbox_backlog_count`, `outbox_age_p95_ms`, `outbox_age_p99_ms`, `outbox_age_slope_ms_per_10s`를 확인한다. count가 잠깐 낮아져도 age slope가 계속 증가하면 조용한 적체로 본다.

### 6.3 Throughput mismatch

`ingest_rps`, `publish_rps`, `consume_rps`를 비교한다.

- ingest > publish 지속: DB write, Outbox insert, Poller, Rabbit publish 경로 의심
- publish > consume 지속: Consumer 처리 지연, DB lock, handler latency, listener 상태 의심
- consume >= publish인데 E2E만 길다: retry lifecycle, histogram overflow, client event timestamp 품질을 확인

### 6.4 Queue depth

RabbitMQ `rabbit_ready_depth`, `rabbit_unacked_depth`, `rabbit_retry_depth`, `rabbit_dlq_depth`를 본다.

- ready 증가: Consumer가 가져가지 못하는 상태
- unacked 증가: 가져간 뒤 처리 또는 ACK가 늦는 상태
- retry depth 증가: retryable failure 압력
- DLQ depth 증가: 최종 격리 또는 poison/contract violation 가능성

### 6.5 Retry / DLQ

`retry_enqueue_rps`, `retry_pressure_ratio`, `rabbit_retry_depth`, `rabbit_dlq_depth`를 함께 본다. DLQ가 증가하면 `dead_letter_message.reason_code`, `payload_hash`, `delivery_count`로 원인을 나눈다.

## 7. 장애 주입 결과 해석

장애 주입에서는 DLQ가 0이어야만 성공인 것은 아니다. 의도한 poison message나 non-retryable failure라면 DLQ 격리는 정상 동작일 수 있다.

구분 기준:

| 상황 | 해석 |
| --- | --- |
| 의도한 poison/contract violation이 DLQ로 이동하고 reason code가 정확함 | 정상 격리 |
| DLQ depth가 증가했지만 reason code가 `UNKNOWN` 위주 | 분류 품질 부족 |
| DLQ로 가야 할 메시지가 retry queue에 계속 남음 | Retry/DLQ 정책 실패 가능성 |
| retry depth가 계속 증가하고 drain되지 않음 | retry pressure 또는 retry routing 문제 |
| DLQ depth가 남아 있지만 테스트 목적상 격리 대상이고 원장 기록이 완료됨 | drain 실패로 보지 않는다. 별도 DLQ 처리/폐기 기준을 적용한다. |
| main/retry/outbox backlog가 남아 제한 시간 내 drain되지 않음 | `drain_failed`로 본다. |

DLQ 잔류와 drain 실패를 구분할 때는 "남은 메시지가 테스트가 의도한 최종 격리 대상인지"를 먼저 확인한다. 의도한 DLQ 메시지는 운영 판단 대상으로 남을 수 있지만, main/retry/outbox backlog 잔류는 파이프라인이 수렴하지 못한 것이다.

## 8. 최종 기록 원칙

결과 요약에는 최소한 아래를 남긴다.

- `test-id`, scenario, git sha, profile
- `verdict`와 실패/UNKNOWN check 이름
- E2E p95/p99, Outbox age, publish/consume, queue depth, retry/DLQ
- clean start와 drain 결과
- 관측 문제인지 실제 성능/신뢰성 문제인지의 분류
- 재실행이 필요한 경우 필요한 수정: scrape 복구, baseline 재생성, 부하 설정 변경, 장애 주입 조건 수정

