# SESSION-07 Project Diagnosis for Issue #47

## 목적

이 문서는 GitHub issue #47의 산출물이다. Phase 1 신뢰성 강화 작업 전에 현재 구조를 production readiness, reliability, observability, maintainability 관점에서 진단하고 keep / refactor / move / remove / defer 판단을 남긴다.

판단 우선순위는 Reliability > Observability > Backpressure > Scalability 이다. 성능 판단도 API latency가 아니라 E2E latency, backlog age, throughput, queue depth, retry/DLQ 상태를 기준으로 한다.

## 요약 판단

현재 구조는 재작성 대상이 아니다. Transactional Outbox, processed_message 기반 Consumer 멱등성, RetryPublishRequest 원장, DLQ 원장, Prometheus snapshot 기반 load-test workflow가 이미 신뢰성 중심의 뼈대를 만든다.

다만 Phase 1 전에 반드시 정리할 리스크는 남아 있다.

- 계층 문서상 `domain`은 외부 의존성이 없어야 하지만 실제 도메인 모델은 JPA entity이다.
- `usecase` 계층이 Spring annotation, Micrometer, Spring Data `Page/Pageable`, AMQP `Message` 일부에 의존한다.
- Consumer listener 두 개가 claim / retry / dead / ack 흐름을 거의 중복 구현한다.
- RetryPublishRequest가 DEAD가 되었을 때 원본 processed_message가 장기 `RETRY_WAIT`로 남을 수 있는 운영 해석이 더 필요하다.
- LT-002/LT-003 최신 실행 증거와 장애 주입 증거는 아직 후속 이슈 범위이다.

## 현재 강점

### Reliability

- AuthError 저장과 Outbox enqueue가 동일 트랜잭션으로 묶이는 방향이 명확하다.
  근거: `AuthErrorWriter`, `OutboxWriter`, `docs/POLICY.md`, `AuthErrorRecordAtomicityIntegrationTest`.
- Outbox claim은 `FOR UPDATE SKIP LOCKED`와 owner 기반 finalize를 사용한다.
  근거: `OutboxJpaRepository.claimBatch`, `markPublished`, `markForRetry`, `markDead`.
- stuck `PROCESSING` 회수 경로가 있다.
  근거: `OutboxReaper`, `OutboxJpaRepository.pickStaleProcessing`, `takeoverStaleProcessing`.
- Consumer는 `processed_message.outbox_id`를 기준으로 처리 원장을 만들고 lease 기반 claim을 사용한다.
  근거: `ProcessedMessageJpaRepository.ensureRowExists`, `claimProcessingUpdate`.
- Retryable consumer failure는 원본 메시지를 바로 재발행하지 않고 `RetryPublishRequest`에 durable하게 기록한다.
  근거: `ConsumerRetryRequestRecorder`, `RetryPublishRequestProcessor`, `RetryPublishRequestJpaRepository`.
- DLQ 도착 메시지는 원장 저장 후 ACK된다.
  근거: `AuthErrorRecordedDlqConsumer`, `AuthErrorAnalysisDlqConsumer`, `DeadLetterMessageRecorder`.

### Observability

- E2E latency, ingest/publish/consume, retry enqueue, DLQ, Outbox age/backlog, Hikari/runtime 설정 지표가 정의되어 있다.
  근거: `MetricsConfig`, `OutboxAgeMetricsScheduler`, `application-local.yml`.
- Runbook이 API 지연, Outbox age, publish silence, RabbitMQ ready/unacked, retry depth, DLQ depth, Hikari pending 순서로 장애 위치를 좁히게 한다.
  근거: `docs/RUNBOOK.md`, `docs/SLI_SLO.md`.
- k6 표준 workflow v1이 clean start, fixed window, drain verification, snapshot/report/verdict를 고정했다.
  근거: `docs/loadtest/AUTOMATED_WORKFLOW.md`, `k6/script/invoke-loadtest-workflow.ps1`.

### Maintainability

- app / usecase / infra / domain 패키지 분리는 전체적으로 읽기 쉽다.
  근거: `docs/ARCHITECTURE.md`, `src/main/java/com/yunhwan/auth/error`.
- 정책 문서와 테스트 시나리오가 함께 존재해 변경 기준을 추적할 수 있다.
  근거: `docs/POLICY.md`, `docs/TEST_SCENARIOS.md`, `docs/TESTING.md`.

## 주요 Findings

| ID | 판단 | 영역 | Finding | 영향 | 권고 |
| --- | --- | --- | --- | --- | --- |
| F1 | refactor | maintainability | `docs/ARCHITECTURE.md`는 `domain`이 외부 계층에 의존하지 않는다고 하지만 도메인 클래스는 JPA annotation과 Spring Data auditing에 의존한다. | 순수 도메인 모델로 오해하면 리팩터링 범위가 잘못 잡힌다. | 단기적으로는 문서를 "JPA entity를 domain package에 둔 실용적 구조"로 정합화한다. 중기적으로 persistence entity 분리는 필요할 때만 한다. |
| F2 | refactor | maintainability | `usecase`가 Spring annotation, transaction, Micrometer, Spring Data `Page/Pageable`, AMQP `Message`에 일부 의존한다. | 테스트와 포트 경계는 유지되지만 hexagonal 경계 설명과 불일치한다. | `usecase`의 Spring 의존은 현재 유지하되, AMQP `Message`와 Page 타입은 포트 DTO로 감싸는 후보로 둔다. |
| F3 | keep | reliability | Outbox claim/finalize는 owner 조건과 `SKIP LOCKED`를 사용한다. | 중복 poller와 동시 처리에서 메시지 상태 충돌을 줄인다. | 유지한다. 변경 시 owner mismatch, duplicate publish, stale takeover 테스트를 먼저 확인한다. |
| F4 | keep | reliability | RetryPublishRequest 원장은 Consumer retry publish 의도를 DB에 남긴 뒤 원본 메시지를 ACK하는 구조다. | RabbitMQ retry publish 실패 시 재발행 의도가 남아 유실 가능성을 낮춘다. | 유지한다. 다만 request DEAD와 processed_message `RETRY_WAIT`의 운영 해석을 후속 정책으로 명확히 한다. |
| F5 | refactor | reliability | `RetryPublishRequestProcessor`가 request를 DEAD로 만들 수 있지만 원본 `processed_message` 상태를 같이 DEAD로 전파하지 않는다. | retry request 발행 자체가 영구 실패하면 운영자는 processed_message가 대기 중인지 발행 원장이 DEAD인지 두 테이블을 같이 봐야 한다. | Phase 1에서 정책 결정: retry publish request DEAD가 원본 processed_message DEAD를 의미하는지, 별도 알림/Runbook으로만 처리할지 정한다. |
| F6 | refactor | maintainability | `AuthErrorRecordedConsumer`와 `AuthErrorAnalysisRequestedConsumer`가 헤더 검증, claim, retry, dead, ack 로직을 중복 구현한다. | 한쪽만 수정되면 ack/retry/DLQ 정합성이 깨질 수 있다. | 공통 listener template 또는 small helper로 추출한다. 단, ack 순서와 transaction boundary가 흐려지지 않게 테스트 먼저 둔다. |
| F7 | keep | observability | metric label은 event_type, queue, result, reason, retry_bucket 중심이다. | requestId/outboxId 같은 고카디널리티 label을 피한다. | 유지한다. 신규 metric 추가 시 `MetricsConfig` taxonomy를 먼저 확장한다. |
| F8 | refactor | observability | DLQ consumer는 원장 저장 후 ACK하지만, observer hook에는 payload 원문을 전달한다. | 테스트/확장 구현체가 payload를 로그로 남기면 안전 로그 정책과 충돌할 수 있다. | `DlqHandler` 계약에 payload 원문 취급 금지 또는 payload hash 기반 observer를 명시한다. |
| F9 | defer | backpressure | Consumer concurrency/prefetch/Hikari pool은 local profile 기준으로 조정되어 있지만 자동 backpressure 제어는 없다. | 부하 증가 시 unacked와 Hikari pending이 같이 오를 수 있다. | #55 Consumer slow와 #54 LT evidence 이후 수치 기반으로 조정한다. |
| F10 | refactor | testing | failure scenario integration tests는 풍부하지만 load/failure injection 실행 증거는 아직 LT-001 중심이다. | 운영 시그니처 가치인 outage simulation 증거가 아직 약하다. | #54~#58 순서로 LT-002/LT-003와 장애 주입 산출물을 남긴다. |
| F11 | keep | runtime | Docker compose는 Postgres/RabbitMQ healthcheck와 Prometheus/Grafana 구성을 제공한다. | 로컬 단일 노드 재현성이 있다. | 유지한다. 운영/HA 보장은 문서에서 명확히 defer한다. |
| F12 | remove | maintainability | `src/main/java/.../AuthErrorRecordHandlerImpl.java`에 "TODO: 실제 비즈니스 처리" 주석이 남아 있다. | 현재 정책상 handler는 analysis_requested enqueue가 핵심인데 TODO가 미완성 기능처럼 보인다. | 동작 변경 없이 주석만 현재 책임에 맞게 바꾸는 후보. |

## 메시징 리스크 검토

### 유실

현재 가장 강한 유실 방지 장치는 AuthError + Outbox 동일 트랜잭션, Outbox owner finalize, RetryPublishRequest 원장, DLQ 원장 저장 후 ACK이다.

남은 유실 리스크는 RabbitMQ unavailable과 retry publish request DEAD 해석이다. RabbitMQ 발행 실패는 Outbox `PENDING` 또는 `PROCESSING` 회수 대상으로 남아야 하며, retry publish request가 영구 실패할 때 원본 processed message를 어떻게 운영적으로 닫을지 정해야 한다.

### 중복

중복은 강한 exactly-once가 아니라 at-least-once + idempotent side effects로 흡수하는 정책이다. `outbox_message.idempotency_key`, `processed_message.outbox_id`, `dead_letter_message.dedupe_key`가 중복 방어 축이다.

중복 발행 또는 중복 delivery 테스트는 이미 일부 존재한다. 향후 RabbitMQ unavailable, consumer slow 장애 주입에서 중복 publish/consume이 실제로 어떻게 보이는지 확인해야 한다.

### 순서

순서는 강하게 보장하지 않는다. Terminal AuthError skip과 DB `next_retry_at` gate가 out-of-order 영향을 줄인다.

`DOMAIN_AUTH_ERROR_NOT_FOUND` DLQ reason은 순서 또는 데이터 정합성 문제를 보는 지표다. 장애 주입 시 이 reason이 증가하면 producer/DB 커밋 순서를 다시 봐야 한다.

### 재처리

Replay API/worker는 아직 없다. `dead_letter_message.replay_status`는 운영 판단 상태만 표현한다. 따라서 DLQ 이슈는 replay 구현이 아니라 DLQ taxonomy, payload 안전성, 원장 검색성까지를 Phase 1 범위로 둔다.

## Transaction Boundary와 ACK/PUBLISH Consistency

| 경로 | 현재 상태 | 리스크 | 판단 |
| --- | --- | --- | --- |
| API -> AuthError + Outbox | 동일 트랜잭션 구조 | DB commit 실패 시 둘 다 없어야 함 | keep |
| Outbox -> RabbitMQ publish -> markPublished | publish 후 DB 상태 finalize | publish 성공 후 markPublished 실패 시 중복 발행 가능 | keep, Consumer idempotency 전제 |
| Consumer success -> markDone -> ACK | markDone 후 ACK | ACK 실패 시 재전달될 수 있으나 DONE claim 실패 후 ACK drop | keep |
| Consumer retry -> markRetryWait + RetryPublishRequest -> ACK | retry 의도 저장 후 ACK | retry request 발행이 장기 실패하면 처리 대기 해석 필요 | refactor/policy |
| Consumer dead -> markDead -> reject(DLQ) | DEAD 기록 후 DLQ reject | reject 실패 시 재전달 가능, markDead 이후 재처리는 claim 차단 | keep |
| DLQ arrival -> dead_letter_message upsert -> ACK | 원장 저장 후 ACK | upsert 실패 시 재전달 가능 | keep |

## 테스트 커버리지 평가

### 커버됨

- API idempotency와 AuthError/Outbox atomicity.
- Outbox poller, retry, reaper 계열 통합 테스트.
- Consumer contract violation, poison payload, retry gate, retry publish request.
- DLQ ledger upsert와 DLQ ACK 흐름.
- Decision guard / atomicity / terminal skip.
- CI에서 quickTest와 integrationTest를 분리 실행.

### 누락 또는 후속

- LT-002/LT-003 최신 표준 artifact 증거.
- RabbitMQ unavailable 실제 장애 주입.
- Consumer slow 실제 장애 주입.
- Retry/DLQ pressure와 poison burst의 부하 기반 증거.
- listener 공통화 전후 ack/retry/DLQ 회귀 테스트 묶음 명시.

## 문서 정합성 평가

- `docs/POLICY.md`, `docs/RUNBOOK.md`, `docs/SLI_SLO.md`는 현재 운영 목적과 잘 맞는다.
- `docs/ARCHITECTURE.md`의 "domain 외부 의존성 없음"은 실제 JPA entity 구조와 충돌한다.
- README는 프로젝트 가치와 현재 상태를 잘 설명하지만, `docs/performance/lt-002-rampup.png`처럼 이미지가 실제 파일로 존재하는지 지속 확인이 필요하다.
- Load test 문서는 #51 이후 표준 workflow와 후속 이슈가 분리되어 좋아졌다.

## Runtime / Observability 평가

- local profile은 management port 18081, RabbitMQ detailed metrics, Prometheus 5s scrape를 기준으로 한다.
- RabbitMQ `publisher-confirm-type=correlated`, `publisher-returns=true`, `mandatory=true`는 유실 방지에 적합하다.
- Hikari max 16, consumer concurrency 4, prefetch 25는 단일 노드 기준 합리적이지만 LT-002/LT-003 결과로 검증해야 한다.
- ELK/Filebeat 흔적은 있으나 현재 핵심 운영 판단은 Prometheus/Grafana + structured JSON logs 중심이다.

## Keep / Refactor / Move / Remove / Defer

| 항목 | 판단 | 이유 |
| --- | --- | --- |
| Transactional Outbox + owner finalize | keep | 현재 reliability 핵심이다. |
| processed_message lease/idempotency | keep | at-least-once 소비의 핵심 방어선이다. |
| RetryPublishRequest 원장 | keep | retry publish 유실 방지 장치다. |
| DLQ ledger 저장 후 ACK | keep | 장애 증거 보존에 중요하다. |
| domain package의 JPA entity 구조 | refactor-later | 당장 동작 안정성을 해치지 않지만 문서와 정합화가 필요하다. |
| usecase의 Spring/Micrometer/Page/AMQP 일부 의존 | refactor | 경계 설명과 맞추거나 포트 DTO로 감싸야 한다. |
| Consumer listener 중복 흐름 | refactor | ack/retry/DLQ 정책 변경 시 드리프트 위험이 있다. |
| `DlqHandler` payload 원문 전달 계약 | refactor | 안전 로그 정책을 계약에 반영해야 한다. |
| 오래된 TODO 주석 | remove | 미완성 오해를 줄인다. |
| AI Incident Agent | defer | #53처럼 reliability/observability 안정화 이후가 맞다. |
| HA/분산 failover | defer | 현재 프로젝트는 local single-node 시그니처가 우선이다. |
| 전체 rebuild | rebuild-if-needed 아님 | 현재 구조는 개선 가능한 상태이며 재작성할 근거가 부족하다. |

## Phase 1 전에 먼저 할 일

1. `docs/ARCHITECTURE.md`를 실제 JPA-domain 구조와 맞춰 정정하거나, persistence entity 분리 계획을 별도 이슈로 만든다.
2. RetryPublishRequest DEAD와 원본 processed_message 상태의 운영 의미를 정책으로 확정한다.
3. `AuthErrorRecordedConsumer` / `AuthErrorAnalysisRequestedConsumer` 공통 흐름을 리팩터링하기 전 회귀 테스트 목록을 고정한다.
4. #59로 LT 결과 해석 가이드를 먼저 작성한다.
5. #54로 LT-002/LT-003 실행 증거를 확보한다.
6. #56, #55, #57, #58 순서로 장애 주입 증거를 남긴다.

## 결론

이 프로젝트는 신뢰성 중심 백엔드 인프라 프로젝트로서 방향이 맞다. 지금 필요한 것은 새 기능을 얹는 것이 아니라, 이미 있는 Outbox / Retry / DLQ / Observability 기반을 더 명확한 운영 증거와 정책 문서로 잠그는 일이다.

따라서 #47의 결론은 "rebuild 하지 말고, 경계 정합성/운영 정책/장애 주입 증거를 Phase 1 선행 작업으로 정리한다"이다.
