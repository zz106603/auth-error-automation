# SLI/SLO 운영 기준

이 문서는 인증 오류 파이프라인이 정상적으로 동작하는지 판단하기 위한 기준이다. 단순히 API 응답 시간만 보지 않고, 요청이 들어온 뒤 Outbox, RabbitMQ, Consumer, Retry/DLQ까지 끝까지 흘러가는지를 함께 본다.

## 핵심 판단 기준

| 항목 | 무엇을 보는가 | 정상 | 경고 | 장애 | SLO 후보 |
| --- | --- | --- | --- | --- | --- |
| API 수집 지연 | `/api/auth-errors` 요청 처리 시간 | p95 300ms 미만 | p95 500ms 이상이 5분 지속 | p95 1초 이상이 5분 지속 또는 5xx 증가 | 10분 구간의 99%에서 p95 500ms 미만 |
| 전체 처리 지연 | 요청 수집부터 Consumer 처리 완료까지 걸린 시간 | p95 30초 미만 | p95 60초 이상이 5분 지속 | p95 180초 이상이 5분 지속 | 10분 구간의 99%에서 p95 60초 미만 |
| Outbox 적체 시간 | 가장 오래 기다리는 Outbox 메시지의 나이 | p95 60초 미만, 증가 추세 없음 | p95 120초 이상 또는 계속 증가 | p95 300초 이상이 5분 지속 | 10분 구간의 99%에서 p95 120초 미만 |
| 발행/소비 불균형 | 발행 속도 대비 소비 속도 | 소비 속도가 발행 속도와 비슷함 | 소비 속도가 발행의 80% 미만 | 소비 속도가 발행의 50% 미만이고 큐가 증가 | 안정 부하에서 소비가 발행의 90% 이상 |
| 재시도 압력 | 재시도 큐로 이동하는 메시지 비율과 적체 | 장애 주입 외에는 거의 0 | 재시도가 소비량의 5% 초과 | 재시도가 20% 초과 또는 재시도 큐 증가 | 알려진 장애 외에는 5% 미만 |
| DLQ 발생 | 최종 실패 메시지 발생률과 DLQ 깊이 | 발생률 0, 깊이 안정 | 5분 이상 DLQ 발생 | DLQ 깊이 증가 또는 특정 원인 급증 | 원인 미확인 DLQ 증가 없음 |
| DB 커넥션 포화 | Hikari pending connection과 사용량 | pending 0 | pending이 3분 이상 0 초과 | pending이 5분 이상 0 초과하고 지연 증가 | 10분 구간의 99%에서 pending 0 |
| RabbitMQ 큐 깊이 | ready/unacked 메시지 수 | ready는 배출되고 unacked는 제한적 | ready가 5분 이상 증가 | ready와 unacked가 모두 높고 배출 안 됨 | 장애 주입 후 큐가 배출됨 |

위 기준은 초기 후보값이다. 부하 테스트 결과와 운영 환경의 실제 트래픽을 기준으로 조정한다.

## 대시보드에서 보는 위치

`LT-001 Minimum` 대시보드 기준으로 다음 순서로 본다.

1. `HTTP Latency`, `HTTP Error Rate`
   API 수집 단계가 느린지 먼저 확인한다.

2. `Outbox Age`, `Publish Silence`
   DB에 쌓인 이벤트가 RabbitMQ로 발행되지 못하는지 확인한다.

3. `RabbitMQ Publish/Consume Rate`, `RabbitMQ Ready/Unacked`
   발행은 되는데 소비가 따라오지 못하는지 확인한다.

4. `Retry Enqueue Rate`, `RabbitMQ Retry Depth`
   일시 실패가 반복되어 재시도 압력이 커졌는지 확인한다.

5. `DLQ Rate`, `RabbitMQ DLQ Depth`
   더 이상 재시도하지 않고 격리된 메시지가 생겼는지 확인한다.

6. `HikariCP Connections`
   DB 커넥션 부족이 API, Poller, Consumer 지연을 동시에 만들고 있는지 확인한다.

## Prometheus 지표

| 판단 기준 | 주요 지표 |
| --- | --- |
| API 수집 지연 | `http_server_requests_seconds_bucket{uri="/api/auth-errors"}` |
| 전체 처리 지연 | `auth_error_ingest_to_consume_seconds_bucket{event_type="auth.error.recorded.v1", queue="auth.error.recorded.q", result="success"}` |
| Outbox 적체 | `auth_error_outbox_backlog_count`, `auth_error_outbox_age_p95`, `auth_error_outbox_age_p99`, `auth_error_outbox_age_slope_ms_per_10s` |
| 발행 속도 | `rate(auth_error_publish_total{result="success"}[1m])` |
| 소비 속도 | `rate(auth_error_consume_total{result="success"}[1m])` |
| 재시도 압력 | `rate(auth_error_retry_enqueue_total[1m])`, retry queue ready/unacked depth |
| DLQ 발생 | `rate(auth_error_dlq_total[1m])`, `rabbitmq_detailed_queue_messages_*{queue=~".*\\.dlq"}` |
| DB 커넥션 포화 | `hikaricp_connections_active`, `hikaricp_connections_pending`, `hikaricp_connections_max`, `auth_error_runtime_hikari_max_pool_size` |
| RabbitMQ 큐 깊이 | `rabbitmq_detailed_queue_messages_ready`, `rabbitmq_detailed_queue_messages_unacked` |

## 추적 키

장애를 추적할 때는 다음 키를 순서대로 연결한다.

1. `requestId`: API 요청과 멱등성 기준.
2. `traceId`: 로그나 분산 추적이 있을 때 요청 흐름을 잇는 기준.
3. `authErrorId`: 저장된 인증 오류 도메인 레코드.
4. `outboxId`: Outbox, RabbitMQ 메시지, Consumer 처리 원장, DLQ를 잇는 핵심 키.
5. `idempotencyKey`: Outbox 이벤트의 중복 방지 키.
6. `payloadHash`: payload 원문을 로그에 남기지 않고 같은 메시지인지 확인하는 키.

최소 추적 흐름:

```text
requestId/traceId
-> auth_error.id
-> outbox_message.id
-> processed_message.outbox_id
-> dead_letter_message.outbox_id
```

## 안전 로그 정책

로그는 원인 추적에 필요한 정보만 남기고, payload 원문이나 민감 정보는 남기지 않는다.

허용:

- `requestId`, `traceId`, `authErrorId`, `outboxId`
- `eventType`, `aggregateType`, `idempotencyKey`
- `payloadHash`, `payloadSizeBytes`
- 상태값, 재시도 횟수, DLQ 원인 코드
- 길이가 제한된 예외 클래스명과 요약 메시지

금지:

- payload 원문
- stacktrace 전문
- access token, session ID
- 사용자 식별자 원문
- 예외 메시지 전체를 그대로 남기는 로그

Metric label에는 고카디널리티 값을 넣지 않는다. `requestId`, `authErrorId`, `outboxId`, stack hash, exception message, user ID, ID가 포함된 raw URI는 label로 사용하지 않는다.

DLQ payload 원문은 `dead_letter_message.payload` 원장에만 보관한다. 일반 로그에는 `payloadHash`와 `payloadSizeBytes`만 남긴다.
