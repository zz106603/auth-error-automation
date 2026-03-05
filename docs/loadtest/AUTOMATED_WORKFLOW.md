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

## 시나리오 실행

- LT-002E: `k6/script/run-lt-002-slice-knee.ps1`
  - k6 실행 + drain 검증 + snapshot + summary 자동 생성

- LT-003: `k6/script/run-lt-003-steady.ps1`
  - k6 steady 실행 + drain 검증 + snapshot + summary 자동 생성

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

## 참고

- Prometheus scrape 간격이 너무 크거나 샘플 수가 부족하면 anomaly로 기록한다.
- counter reset 탐지 시 anomaly로 기록한다.
