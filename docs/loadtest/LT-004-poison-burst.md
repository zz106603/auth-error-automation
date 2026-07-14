# LT-004D Poison Message Burst

## Purpose

LT-004D는 malformed payload 또는 consumer 계약 위반 메시지가 burst로 들어올 때, 정상 메시지 처리와 DLQ 격리가 동시에 안정적으로 유지되는지 확인한다.

이 시나리오는 API 입력 검증이 아니라 RabbitMQ consumer boundary 검증이다. 따라서 정상 트래픽은 k6로 API에 넣고, poison 메시지는 RabbitMQ management API로 `auth.error.exchange`에 직접 publish한다.

## Poison Inputs

Runner는 k6 시작 후 지정된 지연 시간 뒤 recorded routing key(`auth.error.recorded.v1`)로 poison burst를 publish한다.

기본 입력:

- `malformed_json`: JSON 파싱 불가 payload, valid `outboxId/eventType/aggregateType` headers
- `missing_auth_error_id`: JSON은 valid지만 `authErrorId` 누락, valid headers
- `missing_outbox_id`: valid payload와 `eventType/aggregateType` headers, `outboxId` header 누락
- `missing_event_type`: valid payload와 `outboxId/aggregateType` headers, `eventType` header 누락

각 poison payload는 dedupe collapse를 피하기 위해 고유 `requestId` 또는 고유 payload body를 가진다.

## Runbook

앱은 일반 local profile로 실행한다. LT-004C에서 사용한 consumer failure injection은 꺼야 한다.

IntelliJ VM options:

```text
-Dspring.profiles.active=local
```

Run:

```powershell
.\k6\script\run-lt-004-poison-burst.ps1 -ResetStateBeforeRun
```

기본값:

- 정상 API traffic: 10 RPS, 4분
- poison burst: 4개 case x 20건 = 80건
- poison start delay: k6 시작 후 30초

필요하면 아래처럼 조절한다.

```powershell
.\k6\script\run-lt-004-poison-burst.ps1 -ResetStateBeforeRun -TargetRps 10 -SteadyDuration 4m -PoisonPerCase 20
```

## Expected Signals

성공 조건:

- k6 HTTP failure rate 0%
- 정상 메시지는 Outbox publish와 recorded/analysis consumer 경로로 수렴한다.
- poison 메시지는 retry queue로 가지 않는다.
- `auth_error_dlq_total`과 DLQ 원장이 증가한다.
- `dead_letter_message.reason_code`에 아래 분류가 남는다.
  - `PAYLOAD_INVALID_JSON`
  - `PAYLOAD_MISSING_AUTH_ERROR_ID`
  - `CONTRACT_MISSING_OUTBOX_ID`
  - `CONTRACT_MISSING_EVENT_TYPE`
- `dead_letter_message.payload_hash`, `payload_size_bytes`, `delivery_count`가 기록된다.
- main queue, retry queue, Outbox backlog가 post-run drain에서 비워진다.
- 일반 로그와 metric label에는 payload 원문이 남지 않는다. 로그는 outbox id, payload hash, payload size, reason 중심으로 확인한다.

## Failure Interpretation

- retry enqueue rate가 증가한다: poison이 non-retryable contract violation으로 분류되지 않고 retry path로 빠졌을 수 있다.
- DLQ reason code가 `UNKNOWN`이다: header/payload taxonomy 보강이 필요하다.
- DLQ 원장 수가 poison publish 수보다 작다: dedupe key collapse, DLQ consumer 처리 실패, publish routing 실패를 확인한다.
- 정상 메시지 처리량이 크게 흔들린다: poison burst가 consumer thread, DB connection, DLQ recorder를 과도하게 점유했는지 확인한다.
- payload 원문이 일반 로그에 찍힌다: 보안/운영 안정성 문제로 보고 로그를 수정해야 한다.

## Evidence

### Invalid run: `LT-004D-2026-07-14_103449`

이 실행은 정상 API traffic workflow는 성공했지만, poison burst job이 malformed JSON payload 생성 중 PowerShell string format 오류로 실패했다.

- `poison-burst.status.json`: `status=failed`
- `total_attempted=1`
- `total_published=0`

따라서 DLQ가 0으로 나온 것은 poison 격리 성공이 아니라 poison message가 publish되지 않은 결과다. 이 실행은 #58 증거로 사용하지 않는다.

### Valid run: `LT-004D-2026-07-14_104732`

조건:

- 정상 API traffic: 10 RPS, 4분
- Poison burst: 4개 case x 20건 = 80건
- Poison publish window: k6 시작 후 30초, 약 2초 동안 publish

Poison publish 결과:

```text
status: completed
total_attempted: 80
total_published: 80

malformed_json: 20
missing_auth_error_id: 20
missing_outbox_id: 20
missing_event_type: 20
```

Workflow / k6 결과:

- Wrapper final status: success
- Snapshot/report verdict: PASS
- k6 HTTP requests: 2,401
- HTTP failure rate: 0%
- publish / consume post-run delta: 2,401 / 2,401
- retry enqueue RPS avg: 0
- retry pressure ratio max: 0%
- Rabbit retry depth max: 0
- DLQ RPS max: 1.356
- Rabbit DLQ depth max: 0
- post-run drain: PASS, drain time 0초

DLQ 원장:

```text
reason_code                    count
-------------------------------------
CONTRACT_MISSING_EVENT_TYPE       20
CONTRACT_MISSING_OUTBOX_ID        20
PAYLOAD_INVALID_JSON              20
PAYLOAD_MISSING_AUTH_ERROR_ID     20
```

Payload safety / dedupe evidence:

```text
reason_code                    cnt  min_size  max_size  min_delivery  max_delivery  hash_count  distinct_hash_count
-------------------------------------------------------------------------------------------------------------------
CONTRACT_MISSING_EVENT_TYPE     20       177       178             1             1          20                   20
CONTRACT_MISSING_OUTBOX_ID      20       176       177             1             1          20                   20
PAYLOAD_INVALID_JSON            20        91        91             1             1          20                   20
PAYLOAD_MISSING_AUTH_ERROR_ID   20       153       154             1             1          20                   20
```

정상 메시지 최종 원장:

```text
processed_message:
  DONE 4802

outbox_message:
  auth.error.recorded.v1           PUBLISHED 2401
  auth.error.analysis.requested.v1 PUBLISHED 2401

auth_error:
  count 2401
```

로그/metric payload 노출 검토:

- `logs`와 `k6/results/LT-004D-2026-07-14_104732`에서 poison payload 식별 문자열 기준 검색 시 payload 원문 노출은 발견되지 않았다.
- `poison-burst.status.json`은 case별 count만 남기고 payload 원문은 남기지 않는다.
- DLQ consumer 로그는 payload 원문 대신 `outboxId`, `dedupeKey`, `payloadHash`, `payloadSizeBytes`, `reason` 중심으로 기록한다.

판정:

- malformed JSON과 계약 위반 메시지가 retry queue로 가지 않고 DLQ로 격리됐다.
- reason code taxonomy, payload hash, payload size, delivery count가 원장에 남았다.
- 정상 API 메시지는 poison burst와 섞여도 publish/consume 수렴을 유지했다.
- main queue, retry queue, DLQ queue, Outbox backlog는 post-run drain에서 모두 0으로 돌아왔다.

## Conclusion

LT-004D는 #58의 poison message burst acceptance를 충족한다.

- poison message burst 입력 방식이 명시됐다.
- malformed JSON, missing `authErrorId`, missing `outboxId`, missing `eventType` 케이스가 포함됐다.
- 비정상 메시지는 retry 폭주 없이 DLQ로 격리됐다.
- DLQ reason code, payload hash, payload size, delivery count가 확인됐다.
- payload 원문이 일반 로그나 metric label에 노출되지 않는지 검토했다.
- 정상 메시지와 poison message가 섞인 상태에서 정상 처리 경로가 막히지 않았다.
- post-run drain 기준은 main/retry/outbox backlog가 비워지는 것으로 확인했고, DLQ queue는 consumer가 ACK하여 원장 중심으로 해석했다.
