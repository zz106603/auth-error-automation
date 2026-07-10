# LT-003 Steady Load 결과

## 0. 목적

LT-003은 LT-002E에서 찾은 후보 부하를 일정 시간 유지하면서 조용한 붕괴가 발생하는지 확인한다.

판단 기준은 API latency가 아니라 E2E latency, Outbox backlog age, publish/consume 균형, RabbitMQ depth, Retry/DLQ, drain 결과다.

## 1. 실행 요약

| Run ID | Target RPS | Duration | Verdict | 해석 |
| --- | ---: | --- | --- | --- |
| `LT-003-2026-07-09_220234` | 35 | 15m | PASS | 수렴성은 통과했지만 종료부 E2E tail spike가 있어 안정 상한으로 확정하지 않는다. |
| `LT-003-2026-07-09_223737` | 30 | 15m | PASS | 안정 steady 기준선으로 채택한다. |

## 2. 30 RPS Steady 기준선

Source of truth:

- `docs/loadtest/results/LT-003-2026-07-09_223737/prometheus-snapshot.json`
- `docs/loadtest/results/LT-003-2026-07-09_223737/LT-003-2026-07-09_223737-summary.md`

주요 결과:

| Metric | Result |
| --- | ---: |
| k6 HTTP requests | 27,000 |
| publish post-run delta | 27,000 |
| consume post-run delta | 27,000 |
| k6 failed rate | 0% |
| server 5xx rate | 0% |
| ingest->consume p95 max | 938.92 ms |
| ingest->consume p99 max | 1,033.98 ms |
| E2E p95 baseline threshold 초과 지속 | 0 sec |
| E2E p99 baseline threshold 초과 지속 | 0 sec |
| Outbox backlog count max | 0 |
| Outbox age p95/p99 max | 0 ms / 0 ms |
| Retry/DLQ depth | 0 / 0 |
| Rabbit ready max | 0 |
| Rabbit unacked max | 3 |
| Hikari pending max | 0 |
| drain time | 26 sec |

판정:

> Local single-node에서 30 RPS steady는 15분 동안 E2E latency, backlog age, publish/consume, queue depth, retry/DLQ, drain 기준을 통과했다. 이 값을 현재 안정 steady 기준선으로 채택한다.

## 3. 35 RPS 재검증 대상

Source of truth:

- `docs/loadtest/results/LT-003-2026-07-09_220234/prometheus-snapshot.json`
- `docs/loadtest/results/LT-003-2026-07-09_220234/LT-003-2026-07-09_220234-summary.md`

주요 결과:

| Metric | Result |
| --- | ---: |
| k6 HTTP requests | 31,501 |
| publish post-run delta | 31,501 |
| consume post-run delta | 31,501 |
| k6 failed rate | 0% |
| server 5xx rate | 0% |
| ingest->consume p95 max | 6,576.33 ms |
| ingest->consume p99 max | 7,946.44 ms |
| E2E p95 baseline threshold 초과 지속 | 50 sec |
| E2E p99 baseline threshold 초과 지속 | 50 sec |
| Outbox backlog count max | 0 |
| Retry/DLQ depth | 0 / 0 |
| drain time | 0 sec |

판정:

> 35 RPS는 자동 acceptance rule 기준으로 PASS였지만, steady 목적상 종료부 E2E p95/p99가 초 단위로 튄 점을 안정 상한으로 확정하기 어렵다. 35 RPS는 후보로 남기되 30m 재실행 또는 추가 반복 실행으로 tail spike 재현 여부를 확인한다.

## 4. 현재 결론

| 구분 | 결론 |
| --- | --- |
| 안정 steady 기준선 | 30 RPS |
| 재검증 후보 | 35 RPS |
| LT-002E 기준 위험 시작 구간 | 40 RPS 이상 |

## 5. 다음 판단

- 운영 안정성 증거로는 30 RPS LT-003 결과를 우선 사용한다.
- 35 RPS를 상한으로 주장하려면 30분 이상 steady 또는 반복 실행에서 E2E tail spike가 재현되지 않아야 한다.
- 40 RPS 이상은 LT-002E에서 E2E p95 baseline-relative sustained check가 실패했으므로 LT-003 target으로 사용하지 않는다.
