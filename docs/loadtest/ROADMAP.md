# Load Test Roadmap

이 문서는 k6 부하 테스트와 장애 주입 작업의 현재 상태를 추적한다.

판단 우선순위는 Reliability > Observability > Backpressure > Scalability 이다. 따라서 각 항목은 API latency만이 아니라 E2E latency, backlog age, throughput, queue depth, retry/DLQ 상태로 완료 여부를 판단한다.

## 완료

| Issue | 상태 | 완료 기준 | 근거 |
| --- | --- | --- | --- |
| #51 - standard load-test workflow v1 | 완료 | clean start gate, fixed run window, drain verification, Prometheus snapshot, report/verdict 흐름을 표준화한다. | `docs/loadtest/AUTOMATED_WORKFLOW.md`, `k6/script/invoke-loadtest-workflow.ps1`, `k6/script/wait-loadtest-clean.ps1`, `k6/script/verify-loadtest-drain.ps1`, `k6/script/capture-and-report-loadtest.ps1` |
| #59 - LT result interpretation guide | 완료 | PASS/FAIL/UNKNOWN, wrapper 실패, scrape 누락, counter reset, baseline 부재, drain/DLQ 해석 기준을 문서화한다. | `docs/loadtest/RESULT_INTERPRETATION_GUIDE.md` |

## 후속 이슈 후보

| 후속 작업 | 목적 | 완료 기준 |
| --- | --- | --- |
| LT-002/LT-003 execution evidence | ramp-up과 steady load의 실제 실행 증거를 남긴다. LT-002 compact는 빠른 재탐색/sanity check로 사용하고, 공식 knee 결론은 full ramp-up 또는 LT-002E 산출물로 확정한다. | 각 실행에 대해 `docs/loadtest/results/<test-id>/prometheus-snapshot.json`, `prometheus-snapshot.txt`, `<test-id>-summary.md`가 생성되고 PASS/FAIL/UNKNOWN 사유가 기록된다. |
| consumer slow failure injection | Consumer 처리 지연 시 publish/consume imbalance, RabbitMQ ready/unacked, E2E latency가 관측되는지 확인한다. | 지연 주입 방법, 실행 산출물, drain 결과, 병목 판정이 문서화된다. |
| RabbitMQ unavailable failure injection | RabbitMQ 발행 경로 장애에서 메시지가 Outbox backlog로 남고 복구 후 drain되는지 확인한다. | publish failure/silence, Outbox backlog age, 복구 후 drain 결과가 산출물에 남는다. |
| retry/DLQ pressure scenario | retry queue 증가와 DLQ 전환 압력을 표준 workflow로 관측한다. | retry enqueue rate, retry depth, DLQ rate/depth, reason code가 보고서에 반영된다. |
| poison message burst scenario | malformed 또는 계약 위반 메시지가 즉시 DLQ로 격리되는지 검증한다. | poison burst 입력, DLQ reason taxonomy, payload hash/delivery count 기준 중복 처리 결과가 기록된다. |
| payload hash hardening | Outbox payload drift 탐지의 운영 보장 수준을 명확히 한다. | 기존 row backfill 여부, `payload_hash` NOT NULL 전환 여부, 수동 insert 차단 정책이 결정되고 테스트/문서에 반영된다. |
| DLQ replay operations | DLQ 원장 이후의 운영 처리를 정의한다. | replay API/worker를 만들지 여부, operator approval, replay idempotency, retention 기준이 문서화된다. |

## 추적 원칙

- #51은 표준 실행 워크플로우 v1 완료로 닫는다.
- 후속 장애 주입 시나리오는 #51의 workflow 위에 얹는다.
- 장애 주입 작업은 메시지 유실, 중복, 순서, drain 가능성을 먼저 검토한다.
- 최종 보고 수치는 Grafana 수동 조회가 아니라 `docs/loadtest/results/<test-id>/prometheus-snapshot.json`을 기준으로 한다.
