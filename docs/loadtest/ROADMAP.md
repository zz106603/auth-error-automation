# Load Test Roadmap

이 문서는 k6 부하 테스트와 장애 주입 작업의 현재 상태를 추적한다.

판단 우선순위는 Reliability > Observability > Backpressure > Scalability 이다. 따라서 각 항목은 API latency만이 아니라 E2E latency, backlog age, throughput, queue depth, retry/DLQ 상태로 완료 여부를 판단한다.

## 완료

| Issue | 상태 | 완료 기준 | 근거 |
| --- | --- | --- | --- |
| #51 - standard load-test workflow v1 | 완료 | clean start gate, fixed run window, drain verification, Prometheus snapshot, report/verdict 흐름을 표준화한다. | `docs/loadtest/AUTOMATED_WORKFLOW.md`, `k6/script/invoke-loadtest-workflow.ps1`, `k6/script/wait-loadtest-clean.ps1`, `k6/script/verify-loadtest-drain.ps1`, `k6/script/capture-and-report-loadtest.ps1` |
| #59 - LT result interpretation guide | 완료 | PASS/FAIL/UNKNOWN, wrapper 실패, scrape 누락, counter reset, baseline 부재, drain/DLQ 해석 기준을 문서화한다. | `docs/loadtest/RESULT_INTERPRETATION_GUIDE.md` |
| LT-002/LT-003 execution evidence | 완료 | ramp-up/slice와 steady load의 실제 실행 증거를 남기고, PASS/FAIL 사유와 안정 기준선을 기록한다. | `docs/loadtest/results/LT-002E-2026-07-09_213445/`, `docs/loadtest/results/LT-003-2026-07-09_223737/`, `docs/loadtest/LT-003-steady.md` |
| #55 - consumer slow failure injection | 완료 | Consumer 처리 지연 시 E2E latency, publish/consume imbalance, RabbitMQ ready/unacked가 증가하고, retry/DLQ 없이 drain되는지 검증한다. | `docs/loadtest/results/LT-004A-2026-07-13_155542/`, `docs/loadtest/LT-004-consumer-slow.md` |
| #56 - RabbitMQ unavailable failure injection | 완료 | RabbitMQ 발행 경로 장애에서 메시지가 Outbox backlog로 남고, 복구 후 DEAD/DLQ 없이 publish/consume까지 수렴하는지 검증한다. | `docs/loadtest/results/LT-004B-2026-07-13_175403/`, `docs/loadtest/LT-004-rabbitmq-unavailable.md` |
| #57 - retry/DLQ pressure scenario | 완료 | retry queue 증가와 DLQ 전환 압력을 표준 workflow로 관측하고, retry enqueue/depth, DLQ rate/depth, reason code 원장을 기록한다. | `docs/loadtest/results/LT-004C-2026-07-13_183611/`, `docs/loadtest/results/LT-004C-2026-07-13_185310/`, `docs/loadtest/LT-004-retry-dlq-pressure.md` |
| #58 - poison message burst scenario | 완료 | malformed 또는 계약 위반 메시지가 즉시 DLQ로 격리되고, 정상 메시지 처리 경로가 유지되는지 검증한다. | `docs/loadtest/results/LT-004D-2026-07-14_104732/`, `docs/loadtest/LT-004-poison-burst.md` |
| #60 - payload hash hardening | 완료 | Outbox payload drift 탐지의 운영 보장 수준을 명확히 하고, payload_hash NOT NULL/형식 제약과 mismatch 테스트를 반영한다. | `src/main/resources/db/migration/V10__harden_outbox_payload_hash.sql`, `OutboxWriterIntegrationTest`, `docs/POLICY.md` |
| #61 - DLQ replay operations policy | 완료 | replay API/worker 구현은 보류하고, reason code별 replay 금지/조건부 후보, operator approval, audit trail, idempotency 기준을 문서화한다. | `docs/POLICY.md`, `docs/RUNBOOK.md` |
| #62 - Auth failure taxonomy | 완료 | 인증 실패 유형, severity, retryable 분석 속성, security signal, operator action, cluster key 후보를 정의한다. | `docs/AUTH_FAILURE_TAXONOMY.md`, `docs/POLICY.md`, `docs/ARCHITECTURE.md`, `README.md` |
| #63 - AuthError input model expansion | 완료 | taxonomy를 API/DB 모델에 반영하고, provider/client/endpoint/hash context를 원문 개인정보 없이 저장한다. | `V11__extend_auth_error_taxonomy_context.sql`, `AuthError`, `AuthErrorTaxonomyIntegrationTest`, `docs/POLICY.md` |
| #64 - Auth error diagnostic read model | 완료 | MCP/Claude가 사용할 read-only 통계 view와 해석 기준을 추가한다. | `V12__add_auth_error_diagnostic_views.sql`, `docs/MCP_DIAGNOSTIC_READ_MODEL.md`, `AuthErrorDiagnosticReadModelIntegrationTest` |

## 후속 이슈 후보

| 후속 작업 | 목적 | 완료 기준 |
| --- | --- | --- |
| DLQ replay implementation | 정책 확정 이후 실제 replay 실행 기능 필요 여부를 별도 판단한다. | 단건 dry-run, approval, audit ledger, idempotency 회귀 테스트가 설계된 뒤 구현 여부를 결정한다. |
| MCP diagnostic workflow | 인증 실패 통계를 자연어로 조회한다. | read-only MCP tools, 통계 view, Claude 질의 예시, Runbook 연결이 문서화된다. |

## 추적 원칙

- #51은 표준 실행 워크플로우 v1 완료로 닫는다.
- 후속 장애 주입 시나리오는 #51의 workflow 위에 얹는다.
- 장애 주입 작업은 메시지 유실, 중복, 순서, drain 가능성을 먼저 검토한다.
- 최종 보고 수치는 Grafana 수동 조회가 아니라 `docs/loadtest/results/<test-id>/prometheus-snapshot.json`을 기준으로 한다.
