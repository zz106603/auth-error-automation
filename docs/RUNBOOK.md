# 장애 대응 Runbook

이 문서는 장애 중 빠르게 판단하고 복구하기 위한 절차다. 상태 전이와 Retry/DLQ/replay 허용 기준은 [Policy](POLICY.md), metric 임계값은 [SLI/SLO](SLI_SLO.md), 전체 경로와 식별자는 [Architecture](ARCHITECTURE.md)를 단일 기준으로 사용한다.

## Claude/MCP read-only 진단

Claude에서 MCP diagnostic server를 사용하는 경우 질문별 tool 매핑과 답변 형식은 [Claude MCP 운영 진단 가이드](MCP_CLAUDE_DIAGNOSTIC_GUIDE.md)를 따른다.

- MCP 결과는 DB 원장과 집계의 read-only 증거이며 Prometheus 지표와 최근 배포/설정 변경 확인을 대체하지 않는다.
- Claude는 관측 사실, 원인 후보, 미확인 사항을 구분해야 한다.
- replay, row 수정/삭제, queue purge는 MCP가 실행하지 않으며 Runbook의 금지·operator 승인 기준을 우선한다.

## 먼저 5분 안에 확인할 것

1. 최근 배포, 설정 변경, RabbitMQ/PostgreSQL 재시작 여부를 확인한다.
2. `requestId` 또는 `traceId`를 확보한다. 없으면 시간대와 `eventType`으로 범위를 좁힌다.
3. 대시보드에서 아래 순서로 본다.
   API 지연 → Outbox 적체 시간 → 발행 정지 시간 → RabbitMQ ready/unacked → 재시도 큐 깊이 → DLQ 깊이 → Hikari pending
4. 문제가 멈춘 위치를 하나로 좁힌다.
   API 수집 전, Outbox 발행 전, RabbitMQ 큐 내부, Consumer 처리 중, Retry/DLQ 격리 중 하나로 분리한다.
5. 로그는 raw payload나 stacktrace 전문이 아니라 `requestId`, `traceId`, `outboxId`, `reasonCode` 중심으로 확인한다.

## 빠른 판단표

| 보이는 현상 | 가장 의심할 위치 | 먼저 볼 지표 |
| --- | --- | --- |
| API latency와 5xx가 같이 증가 | API 또는 DB | HTTP Latency, HTTP Error Rate, Hikari pending |
| Outbox 적체 시간만 계속 증가 | Poller 또는 RabbitMQ 발행 | Outbox Age, 발행 정지 시간, 발행 실패율 |
| 발행은 되는데 ready 큐가 증가 | Consumer 처리 지연 | Publish/Consume Rate, RabbitMQ ready/unacked |
| unacked가 높고 줄지 않음 | Consumer handler 또는 DB lock | Rabbit unacked, Hikari pending, handler latency |
| retry queue가 증가 | 일시 실패 반복 | Retry Enqueue Rate, Retry Depth, Consumer error |
| DLQ가 증가 | 계약 위반 또는 복구 불가능 메시지 | DLQ Rate, DLQ Depth, `dead_letter_message.reason_code` |

## RabbitMQ 장애 또는 발행 정지

이 상황은 Outbox에 메시지가 남아 있는데 RabbitMQ로 안정적으로 발행되지 못하는 경우다.

### 이렇게 보이면 의심한다

- 발행 정지 시간(`Publish Silence`)이 계속 증가한다.
- `auth_error_publish_total{result!="success"}`가 증가한다.
- Outbox age p95/p99가 증가한다.
- RabbitMQ management UI나 `/metrics/detailed` scrape가 실패한다.

### 확인할 지표

```promql
clamp_min(time() * 1000 - max(auth_error_publish_last_success_epoch_ms), 0)
rate(auth_error_publish_total{result="returned"}[1m])
rate(auth_error_publish_total{result="timeout"}[1m])
auth_error_outbox_age_p95
auth_error_outbox_age_p99
```

### 판단한다

- 발행 정지 시간과 Outbox 적체 시간이 함께 증가하면 발행 경로 문제일 가능성이 높다.
- RabbitMQ metrics scrape도 실패하면 broker 자체 장애 또는 management/exporter 장애를 먼저 확인한다.
- returned가 증가하면 exchange, routing key, binding 설정을 확인한다.
- timeout이 증가하면 RabbitMQ 응답 지연, 네트워크, publisher confirm 지연을 확인한다.

### 즉시 조치

- RabbitMQ process/container 상태, disk alarm, memory alarm을 확인한다.
- exchange, queue, binding이 삭제되었거나 이름이 바뀌었는지 확인한다.
- Outbox row를 수동 삭제하지 않는다. 발행 실패 메시지는 `PENDING` 또는 Reaper 회수 대상인 `PROCESSING` 상태로 남아야 한다.
- 장애 중 중복 발행 가능성은 Consumer 멱등성으로 흡수되어야 하므로, 임의로 processed ledger를 수정하지 않는다.

### 복구 확인

- 발행 정지 시간이 0 근처로 내려간다.
- publish success rate가 회복된다.
- Outbox age p95/p99가 하락한다.
- RabbitMQ ready depth가 증가했다가 최종적으로 배출된다.

## Consumer 지연 또는 큐 적체

이 상황은 RabbitMQ에는 메시지가 들어오지만 Consumer가 처리 속도를 따라가지 못하는 경우다.

### 이렇게 보이면 의심한다

- RabbitMQ ready depth가 증가한다.
- publish rate가 consume rate보다 계속 높다.
- 전체 처리 지연 p95/p99가 증가한다.
- Outbox age는 낮지만 RabbitMQ ready가 높다.

### 확인할 지표

```promql
sum by (queue) (rabbitmq_detailed_queue_messages_ready{queue!=""})
sum by (queue) (rabbitmq_detailed_queue_messages_unacked{queue!=""})
sum(rate(auth_error_publish_total{result="success"}[1m]))
sum(rate(auth_error_consume_total{result="success"}[1m]))
histogram_quantile(0.95, sum by (le) (rate(auth_error_ingest_to_consume_seconds_bucket[1m])))
```

### 판단한다

- ready가 높고 unacked가 낮으면 Consumer가 충분히 가져가지 못하는 상태다.
- unacked가 높고 줄지 않으면 Consumer가 가져간 뒤 handler, DB, 외부 처리에서 막힌 상태다.
- retry depth가 함께 증가하면 단순 처리량 부족보다 반복 실패를 먼저 본다.
- `processed_message.status = 'DEAD'`이고 `last_error`가 `RETRY_PUBLISH_REQUEST_DEAD`로 시작하면 handler 재시도 자체가 실패한 것이 아니라 retry 메시지를 RabbitMQ retry exchange로 재발행하는 원장이 terminal DEAD가 된 상태다. 이 경우 `retry_publish_request`의 `last_publish_error`, `publish_retry_count`, `status`를 함께 확인한다.

### 즉시 조치

- Consumer listener가 실행 중인지 확인한다.
- consumer concurrency, max concurrency, prefetch 설정이 현재 DB pool과 맞는지 확인한다.
- unacked가 높으면 handler 지연, DB lock, 긴 transaction을 확인한다.
- ready만 높으면 channel/connection 장애, routing, listener 중지 여부를 확인한다.

### 복구 확인

- consume rate가 publish rate 이상으로 회복된다.
- ready depth가 감소한다.
- 전체 처리 지연 p95/p99가 정상 구간으로 돌아온다.
- `processed_message`가 `RETRY_WAIT` 또는 `DEAD`로 비정상 누적되지 않는다.

## DB 커넥션 포화

이 상황은 API, Poller, Consumer가 모두 DB 커넥션을 기다리면서 전체 파이프라인이 느려지는 경우다.

### 이렇게 보이면 의심한다

- API latency와 Consumer 처리 지연이 동시에 증가한다.
- `hikaricp_connections_pending`이 0보다 크다.
- Outbox claim, publish 상태 업데이트, processed ledger 업데이트가 늦어진다.

### 확인할 지표

```promql
hikaricp_connections_active
hikaricp_connections_idle
hikaricp_connections_pending
hikaricp_connections_max
auth_error_runtime_hikari_max_pool_size
```

함께 확인할 것:

- PostgreSQL active query
- lock wait
- slow query
- Consumer concurrency와 prefetch

### 판단한다

- pending이 0보다 크면 애플리케이션 스레드가 DB 커넥션을 기다리고 있다는 뜻이다.
- active가 max에 붙고 idle이 0이면 pool이 포화된 상태다.
- pending과 RabbitMQ unacked가 같이 높으면 Consumer가 메시지를 잡은 뒤 DB에서 막혔을 가능성이 크다.

### 즉시 조치

- 장기 transaction과 lock wait를 먼저 확인한다.
- Consumer concurrency/prefetch가 Hikari max pool보다 과도한지 확인한다.
- pool size를 올리기 전 PostgreSQL max connection과 DB 리소스를 확인한다.
- stuck `PROCESSING` 상태는 Reaper 정책으로 회수되게 두고 임의로 상태를 바꾸지 않는다.

### 복구 확인

- pending connection이 0으로 돌아온다.
- API p95와 전체 처리 지연이 정상화된다.
- Outbox age와 RabbitMQ queue depth가 감소한다.

## DLQ 급증

이 상황은 메시지가 더 이상 재시도되지 않고 최종 실패로 격리되는 경우다.

### 이렇게 보이면 의심한다

- `DLQ Rate`가 증가한다.
- `RabbitMQ DLQ Depth`가 증가한다.
- `dead_letter_message.reason_code`의 특정 값이 급증한다.

### 확인할 지표와 원장

```promql
rate(auth_error_dlq_total[1m])
sum by (queue) (rabbitmq_detailed_queue_messages_ready{queue=~".*\\.dlq"})
```

```sql
select reason_code, count(*)
from dead_letter_message
where last_seen_at >= now() - interval '10 minutes'
group by reason_code
order by count(*) desc;
```

### 판단한다

- `PAYLOAD_INVALID_JSON`, `PAYLOAD_MISSING_AUTH_ERROR_ID`가 많으면 producer 계약 위반이다.
- `DOMAIN_AUTH_ERROR_NOT_FOUND`가 많으면 순서 문제 또는 데이터 정합성 문제를 본다.
- `UNKNOWN`이 많으면 실패 분류가 부족한 상태다. 신규 실패 유형을 분리해야 한다.
- 같은 `payload_hash`의 `delivery_count`가 증가하면 같은 메시지가 반복 격리되고 있다.

### 즉시 조치

- `reason_code` 기준으로 원인을 먼저 나눈다.
- payload 원문을 로그로 복사하지 않는다.
- 필요한 경우 `dead_letter_message.payload` 원장에서만 원문을 확인한다.
- producer 계약 위반이면 producer 수정 또는 메시지 생성 로직을 우선 확인한다.

### 복구 확인

- DLQ rate가 0으로 돌아온다.
- DLQ depth가 더 이상 증가하지 않는다.
- 같은 `payload_hash`의 `delivery_count`가 반복 증가하지 않는다.
- 원인별 후속 이슈 또는 producer 수정 기록이 남는다.

## DLQ 원장 처리 기준

DLQ에 도착한 메시지는 `dead_letter_message` 원장에 기록된 뒤 ACK된다.

- 같은 메시지가 반복 도착하면 새 row를 만들지 않고 `delivery_count`, `last_seen_at`을 갱신한다.
- `payload_hash`, `payload_size_bytes`, `outbox_id`, `event_type`으로 메시지를 식별한다.
- `payload` 원문은 DB 원장에만 보관하고 일반 로그에는 남기지 않는다.
- `replay_status`는 현재 운영 판단 상태만 나타낸다. Replay API나 replay worker는 아직 없다.
- DLQ consumer가 원장 저장에 실패하면 RabbitMQ ACK를 하지 않는다. 이 메시지는 consumer 재시작 또는 채널 복구 후 다시 delivery될 수 있다.

## DLQ Replay 운영 판단

현재 시스템은 DLQ replay API나 replay worker를 제공하지 않는다. `replay_status`가 `REPLAYABLE`이어도 자동 재처리 또는 즉시 실행 가능 상태가 아니다.

### 기본 원칙

- 기본값은 replay 금지다.
- replay는 장애 원인을 제거한 뒤 운영자 승인으로만 검토한다.
- payload 원문을 로그, 이슈, 채팅에 복사하지 않는다.
- bulk replay는 금지한다. 구현하더라도 단건 dry-run/report가 먼저 필요하다.
- 같은 업무 이벤트는 기존 idempotency 기준을 유지해야 하며, 새 idempotency key로 중복 side effect를 우회하지 않는다.

### Reason code별 판단

| Reason code | 운영 판단 |
| --- | --- |
| `PAYLOAD_INVALID_JSON` | replay 금지. producer 계약 또는 메시지 생성 로직을 수정한다. |
| `PAYLOAD_MISSING_AUTH_ERROR_ID` | replay 금지. 핵심 식별자가 없어 idempotency를 보장할 수 없다. |
| `CONTRACT_MISSING_*` | replay 금지. header/event 계약 위반을 먼저 수정한다. |
| `DOMAIN_AUTH_ERROR_NOT_FOUND` | replay 금지. DB commit 순서, 데이터 정합성, out-of-order 가능성을 먼저 조사한다. |
| `HANDLER_NON_RETRYABLE` | replay 금지. handler가 복구 불가능 실패로 판단한 원인을 수정한다. |
| `RETRY_EXHAUSTED` | 조건부 후보. 일시 장애가 해소되고 중복 side effect가 없음을 확인한 경우에만 검토한다. |
| `CONSUMER_PROCESSING_FAILED`, `BROKER_REJECTED`, `BROKER_EXPIRED`, `BROKER_MAXLEN`, `UNKNOWN` | 보류. 원인 분류를 먼저 보강하고 operator가 재처리 가능성을 별도 판단한다. |

### Replay를 검토할 때 필요한 기록

Replay 기능을 구현하거나 수동 복구 절차를 수행할 때는 최소한 아래 기록을 남겨야 한다.

- operator id
- operator note
- replay reason
- 원인 해소 근거
- 대상 `dead_letter_message.id`
- original `reason_code`, `payload_hash`, `outbox_id`, `event_type`
- requested/started/completed/failed 시각
- 실패 시 failure reason과 후속 조치

Replay 실패는 자동 반복하지 않는다. 실패한 메시지는 `REPLAY_FAILED` 또는 `BLOCKED`로 다시 격리하고, 재시도하려면 operator가 원인을 재확인해야 한다.

## 추적 SQL

API 요청에서 인증 오류를 찾는다.

```sql
select id, request_id, trace_id, status, stack_hash
from auth_error
where request_id = :requestId
   or trace_id = :traceId;
```

인증 오류에서 Outbox 메시지를 찾는다.

```sql
select id, event_type, status, retry_count, next_retry_at, idempotency_key
from outbox_message
where aggregate_id = cast(:authErrorId as text)
order by id;
```

Outbox 메시지에서 Consumer 처리 상태와 DLQ 원장을 찾는다.

```sql
select outbox_id, status, retry_count, next_retry_at, last_error
from processed_message
where outbox_id = :outboxId;

select source_outbox_id, status, retry_count, publish_retry_count, next_publish_at, last_publish_error
from retry_publish_request
where source_outbox_id = :outboxId
order by created_at desc;

select outbox_id, event_type, reason_code, payload_hash, payload_size_bytes, delivery_count, replay_status
from dead_letter_message
where outbox_id = :outboxId
order by last_seen_at desc;
```

## 로그 작성 기준

허용:

- `requestId`, `traceId`, `authErrorId`, `outboxId`
- `eventType`, `aggregateType`, `idempotencyKey`
- 상태, 재시도 횟수, DLQ 원인 코드
- `payloadHash`, `payloadSizeBytes`
- 길이가 제한된 예외 클래스명과 요약 메시지

금지:

- payload 원문
- stacktrace 전문
- access token, session ID
- 사용자 식별자 원문
- `requestId`, `authErrorId`, `outboxId`, exception message 같은 고카디널리티 값을 metric label로 쓰는 것
