# 인증 실패 운영 분석 시스템

> 외부 시스템의 인증 실패 이벤트를 유실에 강한 비동기 파이프라인으로 수집하고, 장애가 발생했을 때 메시지가 어디에서 멈췄는지 추적·진단하는 프로젝트입니다.

이 프로젝트는 로그인, JWT 발급·검증, OAuth 연동을 제공하는 인증 서비스가 아닙니다. 인증 실패 이벤트를 **안전하게 기록하고 전달하며, Retry/DLQ와 운영 원장에 남은 증거를 바탕으로 장애 원인 후보를 찾는 것**이 목적입니다.

![인증 실패 이벤트 수집과 진단 아키텍처](docs/diagrams/architecture.svg)

## 무엇을 해결하는가

비동기 시스템은 API가 정상 응답해도 뒤쪽에서 메시지가 유실되거나, 반복 처리되거나, 큐에 계속 쌓일 수 있습니다. 이 프로젝트는 다음 질문에 답할 수 있도록 설계했습니다.

- 인증 실패 이벤트와 발행 대기 메시지가 함께 저장되는가?
- 같은 메시지가 다시 전달되어도 부작용이 중복되지 않는가?
- 발행 실패와 일시적인 소비 실패가 복구 가능한 상태로 남는가?
- 처리할 수 없는 메시지가 정상 흐름을 방해하지 않고 격리되는가?
- API 응답 시간뿐 아니라 전체 처리 지연과 적체 상태를 확인할 수 있는가?
- 운영자가 원장과 지표를 근거로 사건을 추적할 수 있는가?

판단 우선순위는 **신뢰성 → 관측 가능성 → 과부하 제어 → 확장성**입니다.

## 핵심 보장

| 보장 | 구현 방식 |
| --- | --- |
| 원자적 수집 | AuthError와 Outbox를 같은 DB transaction에서 저장 |
| 중복 안전성 | API는 `requestId`, Consumer는 `outboxId` 원장으로 중복 제어 |
| 복구 가능한 실패 | 발행·재시도 의도를 DB에 먼저 기록하고 confirm/return 결과로 상태 전이 |
| 실패 격리 | 계약 위반과 재시도 소진 메시지를 사유 코드와 함께 DLQ 원장에 격리 |
| 전체 구간 관측 | 전체 처리 지연, Outbox 적체 시간, 처리량 차이, queue depth와 최종 배출 확인 |
| 읽기 전용 진단 | PostgreSQL 원장을 MCP/Claude로 조회하되 데이터 변경과 replay 기능은 제공하지 않음 |

목표는 강한 exactly-once가 아니라 **at-least-once 전달 + 멱등 처리 + 복구 가능한 원장 + 운영 가시성**입니다.

## 구성요소 역할

| 구성요소 | 담당하는 일 |
| --- | --- |
| Spring Boot | 인증 실패 이벤트 수집, 분류, 상태 전이 |
| PostgreSQL | AuthError, Outbox, Retry, DLQ 운영 원장 |
| RabbitMQ | 비동기 전달, 재시도 큐, DLQ 격리 |
| Prometheus/Grafana | 지연, 적체 시간, 처리량, 큐 깊이의 시간 흐름 확인 |
| MCP/Claude | 실패 유형·provider·사유·상태와 특정 사건의 처리 위치 조회 |
| k6 | 정상 부하, 장애 주입, 복구 후 최종 수렴 검증 |

Elasticsearch/Kibana/Filebeat는 구조화 로그 검색을 위한 선택적 로컬 실험이며 핵심 운영 보장에 포함하지 않습니다.

## 빠른 실행

필요 환경은 Java 21, Docker Compose, PowerShell입니다.

```powershell
Copy-Item .env.example .env
docker compose -f docker-compose.yml -f docker-compose.observability.yml up -d
.\gradlew.bat bootRun --args='--spring.profiles.active=local'
```

| 확인 대상 | 주소 |
| --- | --- |
| API | `http://localhost:8080/api/auth-errors` |
| 애플리케이션 상태 | `http://localhost:18081/actuator/health` |
| Prometheus 수집 endpoint | `http://localhost:18081/actuator/prometheus` |
| Prometheus 화면 | `http://localhost:9090` |
| Grafana 화면 | `http://localhost:3000` |
| RabbitMQ 관리 화면 | `http://localhost:15672` |

`/actuator/prometheus`는 애플리케이션이 metric을 노출하는 주소이고, `:9090`은 Prometheus에서 수집 결과를 조회하는 화면입니다.

종료할 때 데이터 volume을 보존하려면 `-v` 없이 실행합니다.

```powershell
docker compose -f docker-compose.yml -f docker-compose.observability.yml down
```

## 대표 진단 흐름

1. PostgreSQL, RabbitMQ, 애플리케이션, Prometheus/Grafana를 시작합니다.
2. Prometheus에서 애플리케이션과 RabbitMQ 수집 상태를 확인합니다.
3. 여러 인증 실패 유형이 섞인 데모 이벤트를 유입합니다.

```powershell
.\k6\script\run-dm-001-domain-mix.ps1 -ResetStateBeforeRun -TargetRps 3 -DemoDuration 2m
```

4. Grafana에서 API 지연 → Outbox 적체 시간 → 발행/소비 처리량 → 큐 깊이 → Retry/DLQ 순으로 확인합니다.
5. MCP 서버를 준비하고 [MCP 실행 가이드](docs/MCP_DIAGNOSTIC_SERVER.md)에 따라 읽기 전용 DB 계정으로 Claude에 연결합니다.

```powershell
.\gradlew.bat :mcp-diagnostic:installDist
```

6. Claude에서 최근 사건 요약이나 특정 `requestId`/`outboxId`의 처리 위치를 조회합니다.
7. 관측 사실과 원인 후보를 구분하고 [장애 대응 Runbook](docs/RUNBOOK.md)에 따라 추가 확인합니다.

MCP는 row 수정, queue 삭제, replay 같은 운영 조치를 실행하지 않습니다.

| 단계 | 기대 결과 | 실패 시 먼저 확인할 곳 |
| --- | --- | --- |
| 인프라 시작 | PostgreSQL·RabbitMQ healthcheck 통과 | `docker compose ps`, container log |
| 애플리케이션 시작 | `:18081/actuator/health`가 `UP` | 애플리케이션 log, DB/RabbitMQ 환경변수 |
| 관측 연결 | Prometheus에서 두 `up` query가 `1` | Prometheus Targets, `SPRING_BOOT_METRICS_TARGET` |
| domain-mix | HTTP 실패 없이 실행 후 queue가 최종 배출 | k6 wrapper log, Grafana의 Outbox/Retry/DLQ |
| MCP 연결 | tool 목록 조회와 read-only 질의 성공 | MCP stderr, DB role·URL·timeout 설정 |
| 사건 판단 | 관측 사실과 원인 후보가 분리된 답변 | [Runbook](docs/RUNBOOK.md), [MCP 진단 가이드](docs/MCP_CLAUDE_DIAGNOSTIC_GUIDE.md) |

## 검증

```powershell
.\gradlew.bat quickTest
.\scripts\check-testcontainers.ps1
.\gradlew.bat integrationTest
.\gradlew.bat :mcp-diagnostic:test
```

로컬 단일 노드 환경에서 확인한 대표 결과입니다. 아래 수치는 운영환경 성능 보장이 아니라 장애를 재현하고 수렴 여부를 판단한 증거입니다.

| 시나리오 | 확인 결과 | 근거 |
| --- | --- | --- |
| LT-001 기준 부하 | 5 RPS에서 전체 처리 p95 약 548ms, Outbox/Retry/DLQ 적체 없음 | [LT-001](docs/loadtest/LT-001-baseline.md) |
| LT-002E 임계 구간 | 30/35 RPS 구간 통과, 40 RPS부터 전체 처리 p95 지속 조건 실패 | [LT-002E](docs/loadtest/LT-002E-slice-knee.md) |
| LT-003 안정 부하 | 30 RPS를 15분 유지하고 발행·소비 27,000건 일치 | [LT-003](docs/loadtest/LT-003-steady.md) |
| LT-004A Consumer 지연 | 큐와 전체 처리 지연 증가 후 Retry/DLQ 없이 18,000건 수렴 | [LT-004A](docs/loadtest/LT-004-consumer-slow.md) |
| LT-004B RabbitMQ 중단 | 중단 중 Outbox에 2,932건 보존, 복구 후 DEAD/DLQ 없이 모두 발행 | [LT-004B](docs/loadtest/LT-004-rabbitmq-unavailable.md) |
| LT-004C Retry/DLQ 압력 | 일시 실패는 재시도 후 수렴하고, 소진 메시지는 `RETRY_EXHAUSTED`로 원장화 | [LT-004C](docs/loadtest/LT-004-retry-dlq-pressure.md) |
| LT-004D Poison 메시지 | 비정상 80건을 즉시 격리하면서 정상 2,401건 처리 유지 | [LT-004D](docs/loadtest/LT-004-poison-burst.md) |
| DM-001 분류 데모 | 다양한 인증 실패 유형을 유입하고 Retry/DLQ 없이 진단용 분포 생성 | [DM-001](docs/loadtest/DM-001-domain-mix.md) |

상세 결과와 원본 snapshot은 [부하 테스트 문서와 증거](docs/loadtest/README.md)에서 확인할 수 있습니다.

## 보장하지 않는 범위

- 실제 로그인, JWT 발급·검증, OAuth provider 연동
- 강한 exactly-once와 자동 DLQ replay
- 자동 장애 복구 조치와 MCP write tool
- PostgreSQL/RabbitMQ 고가용성, 다중 리전, network partition 검증
- Alertmanager 기반 알림과 자동 incident 생성
- trace backend를 이용한 분산 추적
- 운영환경 보안·보존·접근 통제 정책
- DLQ replay API/worker 구현

## 문서 안내

| 알고 싶은 내용 | 문서 |
| --- | --- |
| 전체 데이터 흐름, 식별자와 장애 격리 | [시스템 아키텍처](docs/ARCHITECTURE.md) |
| 상태 전이, 불변식, Retry/DLQ/replay 규칙 | [처리 정책](docs/POLICY.md) |
| 정상·경고·장애 판단 지표 | [SLI/SLO](docs/SLI_SLO.md) |
| 장애 확인·판단·조치 순서 | [장애 대응 Runbook](docs/RUNBOOK.md) |
| 테스트 종류와 실행 방법 | [테스트와 검증 기준](docs/TESTING.md) |
| 인증 실패 유형과 분류 기준 | [인증 실패 분류 체계](docs/AUTH_FAILURE_TAXONOMY.md) |
| MCP 실행, 보안과 조회 경계 | [MCP 실행 가이드](docs/MCP_DIAGNOSTIC_SERVER.md) |
| 전체 문서와 증거 분류 | [문서 지도](docs/DOCUMENTATION_MAP.md) |
