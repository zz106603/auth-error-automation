# MCP Diagnostic Read Model

이 문서는 MCP diagnostic server가 사용할 read-only 통계 기준을 정의한다.

이 문서는 집계 필드와 해석 기준만 정의한다. 서버 실행·DB role·timeout은 [MCP Diagnostic Server](MCP_DIAGNOSTIC_SERVER.md), 자연어 질문과 답변 경계는 [Claude MCP 운영 진단 가이드](MCP_CLAUDE_DIAGNOSTIC_GUIDE.md)를 따른다.

목표는 Claude 같은 LLM이 원장 테이블을 직접 해석하지 않고도 인증 실패 통계, retry 압력, DLQ reason 분포를 안정적으로 조회하게 만드는 것이다. 모든 read model은 조회 전용이며 AuthError, Outbox, Retry, DLQ 원장의 상태를 변경하지 않는다.

## 1. 원칙

- read model은 PostgreSQL view 중심으로 시작한다.
- view는 aggregate/group by 기반이므로 운영 원장 row를 수정하지 않는다.
- payload 원문, credential, token, raw userId, raw IP는 view에 포함하지 않는다.
- `principal_hash`, `ip_hash`, `stack_hash`, reason code, count, time bucket 중심으로 답한다.
- MCP tool은 view를 그대로 노출하지 않는다. 정확한 `[now()-hoursBack, now]` 범위와 provider/type 필터가 필요한 tool은 원장에 read-only aggregate query를 수행하고, view는 수동 진단과 시간 bucket 탐색에 사용한다.

## 2. Views

| View | 답하는 질문 | 주요 차원 |
| --- | --- | --- |
| `auth_error_hourly_type_stats` | 시간대별 인증 실패 유형 count | hour, error_type, severity, retryable, security_signal |
| `auth_error_context_distribution` | provider/client/httpStatus/endpoint별 실패 분포 | hour, error_type, provider, client_type, http_status, endpoint |
| `auth_error_cluster_summary` | top stackHash/errorType cluster 후보 | error_type, provider, stack_hash, severity |
| `retry_publish_request_summary` | retry publish request 상태와 재발행 압력 | hour, event_type, status |
| `dead_letter_reason_summary` | DLQ reason/replay status 분포 | hour, reason_code, replay_status, dlq_queue, event_type |

view의 `bucket_hour`는 PostgreSQL 세션 시간대의 정각 경계다. view에서 `bucket_hour >= date_trunc('hour', now() - interval 'N hours')`로 조회하면 첫 bucket 전체가 포함되어 N시간보다 넓어질 수 있다. MCP tool은 이 오차를 피하려고 원장의 실제 timestamp를 먼저 제한한 뒤 집계한다. cluster 역시 전체 기간 view의 `last_seen_at`만 자르지 않고 `auth_error.occurred_at` 범위 안에서 count/firstSeen/lastSeen을 다시 계산한다.

## 3. 대표 MCP 질문 매핑

| 질문 | 사용 view |
| --- | --- |
| 지난 1시간 동안 인증 실패가 가장 많은 유형은? | `auth_error_hourly_type_stats` |
| 특정 provider에서 `TOKEN_INVALID_SIGNATURE`가 늘었나? | `auth_error_context_distribution` |
| 보안 신호로 볼 실패가 증가했나? | `auth_error_hourly_type_stats` where `auth_failure_security_signal = true` |
| 가장 큰 cluster 후보는 무엇인가? | `auth_error_cluster_summary` |
| retry publish request가 쌓이고 있나? | `retry_publish_request_summary` |
| DLQ reason code별 현황은? | `dead_letter_reason_summary` |

## 4. 해석 기준

- `auth_failure_security_signal = true`가 증가하면 보안 이벤트 후보로 본다.
- `UNKNOWN_AUTH_ERROR` 비율이 높으면 taxonomy 보강 대상이다.
- `AUTH_PROVIDER_TIMEOUT`, `AUTH_PROVIDER_5XX` 증가는 dependency 장애 후보이며 retry/DLQ 지표와 함께 본다.
- `dead_letter_reason_summary.reason_code`가 payload/contract 계열이면 replay 대상이 아니라 producer 계약 문제로 본다.
- `retry_publish_request_summary.status = DEAD`가 증가하면 retry publish 경로 자체의 장애를 조사한다.

## 5. 범위 밖

- MCP write tool
- DLQ replay 실행
- AuthError/Retry/DLQ 원장 상태 변경
- raw payload 또는 개인정보 원문 반환

## 6. 구현 위치

MCP diagnostic server는 같은 repository 안의 별도 Java Gradle 모듈인 `mcp-diagnostic`에 둔다. 메인 Spring Boot 애플리케이션과 별도 프로세스로 실행하며, 자세한 실행 방식과 tool schema는 [MCP Diagnostic Server](MCP_DIAGNOSTIC_SERVER.md)를 따른다.
