# 시스템 아키텍처와 운영 경계

이 문서는 인증 실패 이벤트의 write path, diagnostic path, 식별자 흐름, 장애 격리 경계와 코드 dependency rule을 설명하는 단일 기준이다. 상태 전이와 Retry/DLQ 규칙은 [Policy](POLICY.md), 실제 장애 대응은 [Runbook](RUNBOOK.md)을 따른다.

## 시스템 책임

이 프로젝트는 외부 인증 시스템에서 발생한 실패 이벤트를 안정적으로 수집하고 운영자가 이해할 수 있는 incident signal로 정규화한다. 실제 로그인, JWT 발급·검증, OAuth provider 연동은 담당하지 않는다.

인증 실패 의미는 [Auth Failure Taxonomy](AUTH_FAILURE_TAXONOMY.md)를 기준으로 `errorType`, `provider`, `clientType`, `endpoint`, hash 기반 식별자에 기록한다. raw userId, sessionId, IP, user-agent는 cluster key나 일반 로그에 사용하지 않는다.

## Write path

```text
외부 시스템
  -> POST /api/auth-errors
  -> AuthError + recorded Outbox (동일 DB transaction)
  -> Outbox Poller claim
  -> RabbitMQ publish confirm/return
  -> Consumer + processed_message ledger
       |-> 성공: DONE 및 후속 Outbox
       |-> 일시 실패: RetryPublishRequest -> TTL Retry Queue
       `-> 계약 위반/재시도 소진: DLQ -> dead_letter_message ledger
```

### 단계별 복구 기준

| 단계 | 복구 가능한 기준 | 실패 시 남는 증거 |
| --- | --- | --- |
| API 수집 | AuthError와 Outbox의 원자적 commit | `auth_error`, `outbox_message` |
| Outbox 발행 | DB 상태와 publisher confirm/return | status, retry count, last error 존재 여부 |
| Consumer 처리 | `processed_message.outbox_id` 멱등 원장 | processing status와 lease |
| Retry | 재발행 의도를 먼저 DB에 저장 | `retry_publish_request` |
| DLQ | DLQ 원장 저장 성공 후 ACK | reason code, delivery count, payload hash |

RabbitMQ 전달은 at-least-once다. 중복 delivery를 정상 가능성으로 보고 DB 원장과 idempotent side effect로 흡수한다. 메시지 유실·중복·순서 검토 없이 Outbox, Retry, DLQ 경로를 변경하지 않는다.

## Diagnostic path

```text
Spring Boot/RabbitMQ metrics -> Prometheus -> Grafana
PostgreSQL 원장과 집계       -> MCP diagnostic server -> Claude
Grafana + MCP 관측 사실      -> Runbook 판단과 승인 경계
```

| 경로 | 답하는 질문 | 답하지 못하는 질문 |
| --- | --- | --- |
| Prometheus/Grafana | 언제부터 지연·backlog·queue가 얼마나 증가했는가 | 특정 사건의 원장 상태와 reason 세부 |
| PostgreSQL/MCP | 어떤 errorType/provider/reason/status가 증가했고 사건이 어디서 멈췄는가 | API latency나 queue depth의 시계열 |
| Runbook | 다음 확인 순서와 금지·승인 조치는 무엇인가 | 관측되지 않은 원인의 확정 |

MCP는 read-only transaction, 전용 DB role, connect/query timeout과 동시 조회 제한을 사용한다. write/replay/operator action tool을 제공하지 않는다. 상세 내용은 [MCP Diagnostic Server](MCP_DIAGNOSTIC_SERVER.md)를 따른다.

## 식별자와 데이터 흐름

```text
requestId / traceId
  -> auth_error.id (authErrorId)
  -> outbox_message.id (outboxId)
  -> RabbitMQ header: outboxId / eventType / payloadHash
  -> processed_message.outbox_id
  -> retry_publish_request.source_outbox_id 또는 dead_letter_message.outbox_id
```

| 식별자 | 용도 |
| --- | --- |
| `requestId` | API 멱등성과 최초 요청 추적 |
| `traceId` | 로그/외부 trace와 요청 연결 |
| `authErrorId` | 인증 실패 도메인 원장 |
| `outboxId` | 발행·소비·Retry·DLQ를 잇는 핵심 키 |
| `idempotencyKey` | 이벤트 생성 중복 방지 |
| `payloadHash` | payload 원문 없이 동일 메시지 확인 |

## 장애 격리 경계

- RabbitMQ 장애가 발생해도 API가 기록한 AuthError와 Outbox는 PostgreSQL에 남는다.
- Consumer 지연은 API commit과 분리되며 queue depth와 E2E latency 증가로 드러난다.
- poison/계약 위반 메시지는 정상 메시지 경로를 반복 점유하지 않도록 즉시 DLQ에 격리한다.
- Retry publish 의도는 ACK보다 먼저 DB에 저장해 consumer 종료 시 유실되지 않게 한다.
- MCP 장애나 query 거절은 write path를 변경하지 않으며 부분 incident snapshot을 정상 결과처럼 반환하지 않는다.
- Grafana, MCP, ELK는 관측/진단 경로이며 도메인 상태를 수정하는 제어 경로가 아니다.

## 구성요소와 필수성

| 구성요소 | 운영 책임 | 필수성 |
| --- | --- | --- |
| Spring Boot | 수집, 상태 전이, publisher/consumer | 핵심 |
| PostgreSQL | 복구 가능한 운영 원장 | 핵심 |
| RabbitMQ | 비동기 전달과 격리 | 핵심 |
| Prometheus/Grafana | 핵심 SLI와 backlog 시계열 | 핵심 관측 |
| MCP/Claude | 원장 기반 read-only 자연어 진단 | 차별화 경로, write path와 분리 |
| k6 | 신뢰성·수렴성 검증 | 검증 도구 |
| ELK/Filebeat | 구조화 로그 검색 실험 | 선택 기능, 핵심 보장 아님 |

## 코드 dependency boundary

헥사고날 구조는 추상적 목표가 아니라 안정성 정책으로 기술 세부 변경이 전파되는 것을 막기 위한 경계다.

- `domain`: 핵심 상태 전이와 JPA entity가 함께 있는 현재 구현의 중심 모델
- `usecase`: domain, usecase DTO, usecase port에만 의존
- `usecase.port`: 저장소, 메시징, 로깅, 분석, 관측 구현을 향한 추상 계약
- `infra`: DB, RabbitMQ, Jackson, logger, metrics 구현
- `app`: HTTP/controller DTO를 command/result로 변환하고 usecase 호출

금지 방향:

- `domain -> usecase/app/infra`
- `usecase -> app`
- `usecase -> infra`

현재 `domain`은 JPA annotation을 가진 영속 엔티티를 포함하므로 순수 domain model은 아니다. persistence 분리는 메시지 유실·중복·순서 위험을 실제로 낮추는 근거가 있을 때만 수행한다.

Outbox, Consumer, Retry, DLQ 정책은 usecase 계층의 핵심 안정성 로직이다. API DTO, RabbitMQ/Jackson, 로그·metric 형식 변경이 이 정책을 우회하지 않아야 한다. Taxonomy 변경은 입력 모델, read model, domain-mix payload와 MCP tool schema에 함께 영향을 주므로 문서 기준 없이 임의 문자열을 확산하지 않는다.
