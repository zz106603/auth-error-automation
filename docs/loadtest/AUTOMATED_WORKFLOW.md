# Load Test 자동화 워크플로우

## 목표

k6 실행 후 수동 Grafana/Prometheus UI 조회 없이, 고정 run window(`t_start`, `t_end`) 기준으로 KPI를 자동 캡처하고 보고서를 생성한다.

## Source of Truth

- 최종 보고의 기준은 `docs/loadtest/results/<test-id>/prometheus-snapshot.json`이다.
- Grafana는 진단/탐색용 보조 도구로 사용한다.

## 결과 폴더 구조

각 실행은 아래 구조로 아티팩트를 만든다.

```text
docs/loadtest/results/<test-id>/
  ├─ k6-artifacts.json
  ├─ run-metadata.json
  ├─ prometheus-snapshot.json
  ├─ prometheus-snapshot.txt
  └─ <test-id>-summary.md
```

## 자동화 스크립트

- `k6/script/capture-prometheus-snapshot.ps1`
  - 입력: `test-id`, `t_start/t_end`(선택), `Prometheus URL`, baseline/rules 경로
  - 동작: Prometheus `query_range`로 KPI 수집, anomaly 탐지, JSON/TXT 출력

- `k6/script/generate-loadtest-report.ps1`
  - 입력: snapshot JSON
  - 동작: KPI 표 + PASS/FAIL/UNKNOWN 판정 + PromQL appendix markdown 생성

- `k6/script/capture-and-report-loadtest.ps1`
  - 위 2개를 순차 실행
- `k6/script/wait-loadtest-clean.ps1`
  - 시나리오 실행 전 파이프라인 clean state gate 확인 (공용)
- `k6/script/verify-loadtest-drain.ps1`
  - 시나리오 종료 후 drain 복귀 확인 (공용)
- `k6/script/reset-loadtest-state.ps1`
  - DB/Rabbit 상태를 테스트 시작 전 강제 초기화 (공용)

## 시나리오 실행

- LT-002E: `k6/script/run-lt-002-slice-knee.ps1`
  - k6 실행 + drain 검증 + snapshot + summary 자동 생성

- LT-003: `k6/script/run-lt-003-steady.ps1`
  - k6 steady 실행 + drain 검증 + snapshot + summary 자동 생성

- LT-001: `k6/script/run-lt-001.ps1`
  - k6 baseline 실행 + drain 검증 + snapshot + summary 자동 생성
  - 기본값으로 `docs/loadtest/baseline/latest-baseline.json` 자동 갱신
  - `-ResetStateBeforeRun` 옵션으로 실행 전 DB/Rabbit 강제 초기화 가능

## 기본 Acceptance Rules

rules 파일: `k6/loadtest-acceptance-rules.json` (스크립트는 `k6/script`에서 호출)

- LT-002E
  - error rate <= 0.2%
  - E2E p95 <= baseline p95 * 3
  - E2E p99 <= baseline p99 * 5
  - outbox slope peak <= 1000 ms/10s
  - publish/consume gap ratio > 5%가 60초 이상 지속되면 FAIL
  - drain time <= 180s

- LT-003
  - error rate <= 0.2%
  - E2E p95 <= baseline p95 * 3
  - E2E p99 <= baseline p99 * 5
  - outbox slope peak <= 500 ms/10s
  - publish/consume gap ratio > 3%가 120초 이상 지속되면 FAIL
  - drain time <= 300s

## Baseline 파일

기본 baseline 파일: `docs/loadtest/baseline/latest-baseline.json`

- LT-001 실행 결과가 갱신되면 이 파일도 함께 갱신해야 baseline-relative 판정이 정확해진다.
- baseline 파일이 없으면 baseline 관련 항목은 `UNKNOWN`으로 평가된다.

## Baseline 캡처/요약 스크립트 (선택/진단용)

- baseline 캡처:
  - `.\k6\script\capture-baseline.ps1`
  - 기본 Actuator URL: `http://localhost:18081` (`/actuator/prometheus`)
  - 기본 출력 경로: `docs/loadtest/baseline-captures`
- baseline 요약:
  - `.\k6\script\summarize-baseline.ps1`
  - 기본 입력 경로: `docs/loadtest/baseline-captures`

두 스크립트의 기본 경로는 모두 **repo root 기준**으로 해석된다.
`capture-manifest.txt`가 없고 `prom-*.txt` 스냅샷이 2개 이상 존재하면,
요약 스크립트가 manifest를 자동 복구한다.
일반 워크플로우에서는 `run-lt-001.ps1`만으로 baseline JSON이 자동 갱신되므로
별도 capture/summarize 실행은 필수 아님.

## Clean Start (권장)

- 테스트 시작 전 강제 초기화:
  - `.\k6\script\reset-loadtest-state.ps1`
- 실행 스크립트에서 즉시 초기화 포함:
  - `.\k6\script\run-lt-001.ps1 -ResetStateBeforeRun`
  - `.\k6\script\run-lt-002-slice-knee.ps1 -ResetStateBeforeRun`
  - 필요시 `-ResetPurgeAllQueues`로 `amq.*` 포함 전체 purge

## 참고

- Prometheus scrape 간격이 너무 크거나 샘플 수가 부족하면 anomaly로 기록한다.
- counter reset 탐지 시 anomaly로 기록한다.
