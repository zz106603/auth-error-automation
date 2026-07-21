# 문서 지도와 정리 기준

이 문서는 이슈 #68에서 정리한 문서 inventory다. 처음 방문한 사용자는 README에서 시작하고, 아래 canonical 문서를 목적에 따라 읽는다. 중간 기록과 부하 테스트 원본은 사용자 탐색 경로에서 분리하되 재현과 판단 근거를 위해 보존한다.

## 권장 읽기 순서

1. [README](../README.md): 프로젝트 정체성, 보장 범위, 빠른 실행과 대표 데모
2. [Architecture](ARCHITECTURE.md): write/diagnostic path, 식별자와 장애 격리
3. [Policy](POLICY.md): 상태 전이, 불변식, Retry/DLQ/replay 규칙
4. [SLI/SLO](SLI_SLO.md): 정상·경고·장애 판단 지표
5. [Runbook](RUNBOOK.md): 실제 장애 대응 순서와 금지 조치
6. [Testing](TESTING.md): 코드·통합·MCP·부하 검증 방법
7. [MCP Diagnostic Server](MCP_DIAGNOSTIC_SERVER.md): read-only 진단 실행과 보안 경계

## 파일 단위 inventory

### 진입·정책·운영 문서

| 파일 | 분류 | 결론 |
| --- | --- | --- |
| `../README.md` | Canonical | 프로젝트 진입점으로 유지 |
| `ARCHITECTURE.md` | Canonical | 전체 경로·식별자·격리 경계의 단일 기준 |
| `POLICY.md` | Canonical | 상태 전이·불변식·Retry/DLQ/replay 정책의 단일 기준 |
| `RUNBOOK.md` | Canonical | 장애 대응 절차의 단일 기준 |
| `SLI_SLO.md` | Canonical | 정상·경고·장애 지표의 단일 기준 |
| `TESTING.md` | Canonical | 테스트 계층·명령·실패 분류의 단일 기준 |
| `AUTH_FAILURE_TAXONOMY.md` | Canonical | 인증 실패 분류의 단일 기준 |
| `TEST_SCENARIOS.md` | Supporting specification | Policy 기반 상세 테스트 시나리오로 보존 |
| `DOCUMENTATION_MAP.md` | Canonical navigation | 전체 문서 분류와 읽기 순서 |
| `../AGENTS.md` | Repository governance | Codex 작업 지침이며 사용자 문서 탐색에서 제외 |

### MCP 문서

| 파일 | 분류 | 결론 |
| --- | --- | --- |
| `MCP_DIAGNOSTIC_SERVER.md` | Canonical | 실행·DB role·timeout·tool 보안 경계 |
| `MCP_DIAGNOSTIC_READ_MODEL.md` | Canonical | 집계 필드와 해석 기준 |
| `MCP_CLAUDE_DIAGNOSTIC_GUIDE.md` | Canonical | 자연어 질문·답변·Runbook 연결 |
| `../mcp-diagnostic/README.md` | Module guide | 모듈 단독 실행과 개발 명령으로 보존 |

### 부하 테스트 문서

| 파일 | 분류 | 결론 |
| --- | --- | --- |
| `LOAD_TEST_GUIDE.md` | Supporting guide | E2E·적체·처리량 개념 설명으로 보존; 실행 기준은 workflow로 위임 |
| `LOAD_TEST_CHECKLIST.md` | Supporting checklist | 상세 준비·중단 조건 확인용으로 보존; 최신 상태 문구 정정 완료 |
| `loadtest/README.md` | Evidence navigation | 부하 테스트 진입점으로 유지 |
| `loadtest/AUTOMATED_WORKFLOW.md` | Canonical | 실행·drain·snapshot 생성의 단일 기준 |
| `loadtest/RESULT_INTERPRETATION_GUIDE.md` | Canonical | PASS/FAIL/UNKNOWN 해석의 단일 기준 |
| `loadtest/ROADMAP.md` | Planning record | 완료·후속 상태 기록으로 보존, 결과 기준으로 사용하지 않음 |
| `loadtest/LT-001-baseline.md` | Supporting evidence | 기준 부하 증거 |
| `loadtest/LT-002-rampup.md` | Supporting evidence | ramp-up 증거 |
| `loadtest/LT-002E-slice-knee.md` | Supporting evidence | 최신 임계 구간 결론 포함 |
| `loadtest/LT-002E-lower-narrow.md` | Supporting evidence | 낮은 구간 재탐색 증거 |
| `loadtest/LT-002E-hypothesis-experiments.md` | Historical experiment | 과거 가설임을 문서 상단에 명시하고 보존 |
| `loadtest/LT-003-steady.md` | Supporting evidence | 안정 부하 증거 |
| `loadtest/LT-004-consumer-slow.md` | Supporting evidence | Consumer 지연 증거 |
| `loadtest/LT-004-rabbitmq-unavailable.md` | Supporting evidence | RabbitMQ 중단 증거 |
| `loadtest/LT-004-retry-dlq-pressure.md` | Supporting evidence | Retry/DLQ 압력 증거 |
| `loadtest/LT-004-poison-burst.md` | Supporting evidence | poison 격리 증거 |
| `loadtest/DM-001-domain-mix.md` | Supporting evidence | taxonomy·MCP 데모 증거 |
| `loadtest/results/*/*-summary.md` 17개 | Local generated evidence | Git ignore 대상 원본 실행 요약; 시나리오 문서에 결론 이관 후 로컬 보존 |

### 중간 기록

| 파일 | 분류 | 결론 |
| --- | --- | --- |
| `agent/README.md` | Historical navigation | 세션 기록의 비권위성을 명시 |
| `agent/SESSION-01-policy-review.md` | Historical record | 정책 결정 배경 보존 |
| `agent/SESSION-02-test-review.md` | Historical record | 테스트 review 배경 보존 |
| `agent/SESSION-03-test-scenarios-vs-tests.md` | Historical record | 과거 coverage 분석 보존 |
| `agent/SESSION-04-test-plan.md` | Historical record | 과거 실행 계획 보존 |
| `agent/SESSION-05-LoadTest-01.md` | Historical record | 부하 테스트 초기 판단 보존 |
| `agent/SESSION-06-LT-001-review.md` | Historical record | LT-001 review 배경 보존 |
| `agent/SESSION-07-project-diagnosis.md` | Historical record | 프로젝트 진단 배경 보존 |

### 다이어그램·Compose·관측 설정

| 파일 | 분류 | 결론 |
| --- | --- | --- |
| `diagrams/architecture.svg` | Canonical diagram | write/diagnostic path를 함께 표현 |
| `diagrams/outbox-state.svg` | Supporting diagram | Outbox 상태 흐름 증거로 보존 |
| `performance/lt-002-rampup.png` | Supporting evidence | 과거 ramp-up 시각 증거로 보존 |
| `../docker-compose.yml` | Canonical runtime config | PostgreSQL·RabbitMQ 핵심 인프라 |
| `../docker-compose.observability.yml` | Canonical runtime config | Prometheus·Grafana 관측 스택 |
| `../observability/prometheus.yml` | Canonical observability config | Spring Boot·RabbitMQ scrape 설정 |
| `../observability/grafana/dashboards/lt-001-min.json` | Canonical dashboard | 핵심 SLI 대시보드 |
| `../observability/grafana/provisioning/dashboards/dashboards.yml` | Supporting config | dashboard provisioning |
| `../observability/grafana/provisioning/datasources/datasource.yml` | Supporting config | Prometheus datasource provisioning |
| `../observability/rabbitmq/enabled_plugins` | Supporting config | RabbitMQ management·Prometheus plugin |
| `../elk/docker-compose.yml` | Optional experiment | 로컬 Elasticsearch/Kibana/Filebeat, 핵심 실행에서 제외 |
| `../elk/filebeat.yml` | Optional experiment | 구조화 로그 수집 실험 |

### 정리 결론

- 현재 유효 내용을 잃지 않고 삭제할 수 있는 Outdated/Orphan 문서는 발견하지 못했다.
- 기존 merge 후보 두 문서는 개념 설명과 실행 checklist라는 독립 책임을 확인해 Supporting으로 확정했다.
- 중간 세션 기록은 `agent/`, 생성 결과는 `loadtest/results/`에 이미 물리적으로 분리되어 있어 이동하지 않았다.
- ELK는 Optional experiment로 유지하며 README Quick Start와 핵심 보장에서 제외했다.
- 삭제·이동이 없어 경로 호환성은 유지했고, 오래된 LT 미완료 표현만 현재 상태로 정정했다.

## 문서별 단일 책임

| 문서 | 포함하는 내용 | 포함하지 않는 내용 |
| --- | --- | --- |
| README | 빠른 이해, 실행, 대표 데모, 검증 요약, 탐색 링크 | 상태 머신 전체, 상세 SQL, 모든 실험 기록 |
| Architecture | 전체 경로, 책임, 식별자, 장애 격리, dependency boundary | Retry 횟수와 replay 승인 절차의 상세 |
| Policy | 상태 전이, 불변식, 계약, Retry/DLQ/replay 규칙 | 실제 장애 상황의 단계별 대응 |
| Runbook | 증상별 확인·판단·조치·복구 확인 | 정책 자체의 재정의 |
| SLI/SLO | 지연·backlog·throughput·queue 판단 기준 | 사건별 원인 확정 |
| Testing | 테스트 계층, 명령, 실패 분류 | 부하 결과의 상세 해석 |
| MCP Server | 실행, DB role, timeout, tool과 보안 경계 | Grafana 시계열 분석 대체 |

## 증거 보존 원칙

- `docs/loadtest/results/`의 snapshot과 summary는 오래됐다는 이유로 삭제하지 않는다.
- `k6/results/`는 실행 wrapper와 drain artifact의 위치이며 최종 보고 기준은 `docs/loadtest/results/`다.
- `docs/agent/`는 결정 당시 문맥을 보존하지만 현재 구현과 충돌하면 canonical 문서를 우선한다.
- 파일 이동·삭제 전 README와 Markdown 링크, script/test 경로 참조, 대체 문서 존재 여부를 확인한다.
- ELK는 실제 동작 수준을 검증하기 전 핵심 observability나 완료 조건으로 표현하지 않는다.

## 정리 후 검증

- 모든 내부 Markdown 링크가 존재하는 파일과 anchor를 가리키는지 검사한다.
- compose port, 환경변수, 실행 명령을 실제 설정과 대조한다.
- metric, queue, status, reason code 이름을 코드와 대조한다.
- 부하 테스트 summary가 원본 snapshot과 연결되는지 확인한다.
- README만 읽은 신규 사용자가 core stack을 시작하고 domain-mix → Grafana → MCP 진단 흐름을 재현할 수 있는지 확인한다.
