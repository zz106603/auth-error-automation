# Load Test 자동화 워크플로우

## 목표

k6 부하 테스트를 일회성 TPS 측정이 아니라 비동기 파이프라인의 수렴성과 장애 징후를 반복 검증하는 흐름으로 고정한다.

이 문서는 GitHub issue #51의 완료 산출물이다. #51은 standard load-test workflow v1 완료로 닫고, 남은 실행 증거와 장애 주입 시나리오는 `docs/loadtest/ROADMAP.md`의 후속 항목으로 추적한다.

표준 실행 순서:

```text
clean start gate
-> fixed k6 run window
-> drain verification
-> Prometheus snapshot
-> report/verdict
```

API latency만 빠른 상태는 성공으로 보지 않는다. 최종 판정은 E2E latency, Outbox age, publish/consume imbalance, retry pressure, DLQ depth, drain 결과를 함께 본다.

## Source of Truth

- 최종 보고의 기준은 `docs/loadtest/results/<test-id>/prometheus-snapshot.json`이다.
- 사람이 읽는 요약은 `docs/loadtest/results/<test-id>/<test-id>-summary.md`이다.
- Grafana는 진단/탐색용 보조 도구로 사용한다.
- k6 stdout과 wrapper 상태는 `k6/results/<test-id>/`에 남긴다.

## 표준 산출물 구조

각 실행은 아래 구조를 만든다.

```text
k6/results/<test-id>/
  ├─ <scenario>.stdout.log
  ├─ <scenario>-<test-id>.log
  ├─ wrapper-execution.log
  ├─ wrapper-state.json
  ├─ pre_run_gate.samples.jsonl
  ├─ pre_run_gate.summary.json
  ├─ post_run_drain.samples.jsonl
  └─ post_run_drain.summary.json

docs/loadtest/results/<test-id>/
  ├─ k6-artifacts.json
  ├─ run-metadata.json
  ├─ prometheus-snapshot.json
  ├─ prometheus-snapshot.txt
  └─ <test-id>-summary.md
```

실패 시에는 가능한 위치에 아래 파일도 남긴다.

```text
k6/results/<test-id>/wrapper.failure.json
k6/results/<test-id>/FAILED-WRAPPER.txt
docs/loadtest/results/<test-id>/wrapper.failure.json
docs/loadtest/results/<test-id>/FAILED-WRAPPER.txt
```

## 공통 실행 Helper

모든 LT runner는 `k6/script/invoke-loadtest-workflow.ps1`를 통해 실행한다.

이 helper가 담당하는 일:

- 필요 시 `reset-loadtest-state.ps1` 실행
- `wait-loadtest-clean.ps1`로 clean start gate 확인
- Docker 기반 k6 실행과 stdout 파일 저장
- `verify-loadtest-drain.ps1`로 post-run drain 확인
- `capture-and-report-loadtest.ps1`로 Prometheus snapshot과 Markdown report 생성
- wrapper 상태와 실패 원인 artifact 기록

개별 runner는 시나리오별 차이만 넘긴다.

- LT-001: baseline script, baseline 자동 갱신
- LT-002: ramp-up script
- LT-002E: knee slice script, `SLICE_PROFILE`, 초기 `[SLICE_START]` marker 확인
- LT-003: steady script, `TARGET_RPS`, `STEADY_DURATION`

## 실행 명령

Baseline:

```powershell
.\k6\script\run-lt-001.ps1
```

Ramp-up:

```powershell
.\k6\script\run-lt-002.ps1
```

Knee slice:

```powershell
.\k6\script\run-lt-002-slice-knee.ps1
.\k6\script\run-lt-002-slice-knee.ps1 -SliceProfile lower-narrow
```

Steady load:

```powershell
.\k6\script\run-lt-003-steady.ps1 -TargetRps 85 -SteadyDuration 15m
```

테스트 전 DB/RabbitMQ 상태를 강제로 비우고 시작해야 하면 공통 옵션을 사용한다.

```powershell
.\k6\script\run-lt-001.ps1 -ResetStateBeforeRun
.\k6\script\run-lt-002-slice-knee.ps1 -ResetStateBeforeRun
```

## Observability Preflight

LT 실행 전 Prometheus가 앱과 RabbitMQ를 모두 scrape하고 있는지 먼저 확인한다. 이 단계가 실패하면 k6를 시작하지 않는다.

Prometheus UI 또는 API에서 아래 쿼리가 정상이어야 한다.

```promql
up{job="spring-boot"} == 1
up{job="rabbitmq"} == 1
```

핵심 시계열도 비어 있으면 안 된다.

```promql
auth_error_outbox_backlog_count
auth_error_outbox_age_p95
rabbitmq_detailed_queue_messages_ready{queue!=""}
hikaricp_connections_active
```

`spring-boot` scrape가 실패하면 애플리케이션이 local profile의 management port `18081`로 떠 있는지 확인한다. `rabbitmq` scrape가 실패하면 RabbitMQ Prometheus endpoint `:15692/metrics/detailed`와 observability compose 상태를 먼저 확인한다.

## Clean Start Gate

테스트 시작 전 `wait-loadtest-clean.ps1`가 아래 조건을 확인한다.

- RabbitMQ ready depth가 허용 범위 이하
- RabbitMQ unacked depth가 허용 범위 이하
- retry queue depth가 허용 범위 이하
- DLQ depth가 허용 범위 이하
- Outbox backlog count가 허용 범위 이하
- Outbox age p95/p99가 허용 범위 이하
- Hikari pending connection이 허용 범위 이하

clean state가 지정 시간 동안 유지되지 않으면 k6를 시작하지 않는다.

실패 분류:

- `clean_start_failed`

## Fixed Run Window

k6 시작 시각과 종료 시각을 wrapper가 UTC로 기록한다.

이 `t_start`, `t_end`는 Prometheus `query_range`의 기준 window가 된다. 수동 Grafana 조회값은 최종 보고 수치로 사용하지 않는다.

## Drain Verification

k6 실행이 끝난 뒤 `verify-loadtest-drain.ps1`가 파이프라인이 다시 비는지 확인한다.

drain 실패는 테스트 실패다. API 요청이 모두 2xx였더라도 backlog가 배출되지 않으면 성공으로 처리하지 않는다.

실패 분류:

- `drain_failed`

## Snapshot / Report / Verdict

`capture-and-report-loadtest.ps1`는 두 단계를 실행한다.

1. `capture-prometheus-snapshot.ps1`
   - run window 기준 Prometheus 지표 수집
   - drain 완료 시점까지 포함한 post-run counter delta 수집
   - baseline-relative checks 계산
   - anomaly 기록
   - `prometheus-snapshot.json` 생성

2. `generate-loadtest-report.ps1`
   - KPI 요약
   - acceptance checks
   - `PASS`, `FAIL`, `UNKNOWN` verdict
   - PromQL appendix 생성

판정 기준은 `k6/loadtest-acceptance-rules.json`을 따른다.

결과 해석 절차는 `docs/loadtest/RESULT_INTERPRETATION_GUIDE.md`를 따른다. 이 문서는 `prometheus-snapshot.json` 읽는 순서, `PASS`/`FAIL`/`UNKNOWN` 의미, wrapper 실패 유형, scrape 누락과 실제 성능 실패 구분 기준을 정의한다.

## 실패 원인 분류

| 분류 | 의미 | 대표 산출물 |
| --- | --- | --- |
| `reset_state_failed` | 실행 전 강제 초기화 실패 | `wrapper.failure.json` |
| `clean_start_failed` | clean start gate 실패. k6 미시작 | `pre_run_gate.summary.json`, `FAILED-WRAPPER.txt` |
| `k6_failed` | k6 프로세스 실패 또는 marker 확인 실패 | k6 stdout log, `wrapper.failure.json` |
| `drain_failed` | 실행 후 backlog가 시간 안에 배출되지 않음 | `post_run_drain.summary.json`, `CONTAMINATED.txt` |
| `snapshot_or_report_failed` | Prometheus snapshot 또는 report 생성 실패 | `capture-and-report.failure.json` |

snapshot 내부의 `UNKNOWN`은 테스트 성공이 아니다. 주로 baseline 부재, scrape 누락, 샘플 부족, counter reset, 필수 artifact 누락처럼 판정에 필요한 근거가 부족한 경우다.

필수 snapshot query가 no-data이면 LT-001 baseline은 PASS로 채택하지 않는다. 특히 E2E latency, overflow signal, publish/consume, Outbox backlog/age, RabbitMQ depth, Hikari 지표는 모두 샘플이 있어야 한다.

k6 run window 끝 경계에서는 마지막 Prometheus scrape가 늦게 들어와 counter가 작게 보일 수 있다. 총량 검증은 run-window counter의 마지막 값만 보지 않고 drain 완료 이후 `post-run counter delta`를 함께 확인한다.

## Baseline 파일

기본 baseline 파일:

```text
docs/loadtest/baseline/latest-baseline.json
```

LT-001이 성공하면 기본값으로 baseline을 갱신한다. baseline 파일이 없거나 핵심 값이 비어 있으면 baseline-relative check는 `UNKNOWN`으로 기록된다.

Baseline 자동 갱신은 snapshot/report verdict가 `PASS`일 때만 수행한다. `post_run_counter_coverage`가 `FAIL`이면 HTTP 요청은 성공했더라도 Outbox publish/consume/timer 증가량이 부족한 실행이므로 baseline 갱신 대상이 아니다. `LT-001-2026-06-25_152427`은 LT-001 `requestId` 재사용으로 dedup 처리된 invalid run이므로 baseline으로 사용하지 않는다.

## 장애 주입 시나리오 범위

#51은 표준 실행 흐름 v1을 고정한 것으로 완료한다. 장애 주입 시나리오는 이 workflow 위에 얹는 후속 이슈로 분리한다.

후속으로 붙일 시나리오:

- LT-002/LT-003 execution evidence
- consumer slow failure injection
- RabbitMQ unavailable failure injection
- retry/DLQ pressure scenario
- poison message burst scenario
- LT result interpretation guide (`docs/loadtest/RESULT_INTERPRETATION_GUIDE.md`)

이 시나리오들도 동일하게 clean start gate, fixed run window, drain verification, Prometheus snapshot, report/verdict를 따라야 한다.

후속 작업의 완료 기준은 `docs/loadtest/ROADMAP.md`를 따른다.
