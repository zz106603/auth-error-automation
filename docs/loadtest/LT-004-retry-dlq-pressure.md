# LT-004C Retry / DLQ Pressure

## Purpose

LT-004C는 Consumer 처리 중 일시 실패가 발생할 때 retry enqueue rate와 retry queue depth가 증가하고, 정책상 복구 가능한 메시지는 최종 성공으로 수렴하는지 확인한다.

또한 `retry-until-dead` 모드에서는 선택된 메시지를 max retry까지 계속 실패시켜 DLQ 전환, DLQ rate, `dead_letter_message.reason_code` 원장 근거를 확인한다.

## Failure Injection

주입 위치는 recorded consumer가 `processed_message` claim에 성공한 뒤 handler 호출 직전이다.

설정:

```yaml
auth-error:
  loadtest:
    consumer-failure:
      recorded-mode: off | retry-once | retry-until-dead
      recorded-percent: 0..100
      recorded-fail-until-retry-count: 1
```

- `retry-once`: 선택된 메시지를 `x-retry-count < recorded-fail-until-retry-count` 동안만 retryable failure로 실패시킨다.
- `retry-until-dead`: 선택된 메시지를 계속 retryable failure로 실패시켜 max retry 정책에 따라 DEAD/DLQ 전환을 관측한다.
- 선택 대상은 `requestId` hash 기반으로 결정한다. 같은 메시지는 retry delivery에서도 같은 선택 결과를 가진다.

이 주입은 API ingest, Outbox publish, RabbitMQ routing을 바꾸지 않는다.

## Runbook

### Retry pressure and recovery

IntelliJ VM options:

```text
-Dspring.profiles.active=local -Dauth-error.loadtest.consumer-failure.recorded-mode=retry-once -Dauth-error.loadtest.consumer-failure.recorded-percent=20 -Dauth-error.loadtest.consumer-failure.recorded-fail-until-retry-count=1
```

Run:

```powershell
.\k6\script\run-lt-004-retry-dlq-pressure.ps1 -ResetStateBeforeRun -FailureMode retry-once -ExpectedFailurePercent 20 -ExpectedFailUntilRetryCount 1
```

### Retry until DLQ

IntelliJ VM options:

```text
-Dspring.profiles.active=local -Dauth-error.loadtest.consumer-failure.recorded-mode=retry-until-dead -Dauth-error.loadtest.consumer-failure.recorded-percent=5 -Dauth-error.loadtest.consumer-failure.recorded-fail-until-retry-count=1
```

Run:

```powershell
.\k6\script\run-lt-004-retry-dlq-pressure.ps1 -ResetStateBeforeRun -FailureMode retry-until-dead -ExpectedFailurePercent 5 -TargetRps 10 -SteadyDuration 4m -AllowedDlqDepth 100000
```

`retry-until-dead`는 DLQ 전환이 의도된 결과다. 따라서 drain에서는 main queue, retry queue, Outbox backlog가 비었는지를 보고, DLQ queue 또는 DLQ 원장 잔류는 별도 증거로 판단한다.

## Expected Signals

`retry-once` 성공 조건:

- k6 HTTP failure rate 0%
- `retry_enqueue_rps`와 `rabbit_retry_depth`가 run window 중 증가한다.
- retry 이후 `consume_total{result="success"}`가 최종 publish 수와 수렴한다.
- post-run drain이 성공한다.
- DLQ rate/depth와 `dead_letter_message` 신규 row가 0이다.
- 중복 delivery가 있어도 `processed_message.outbox_id` 기준으로 최종 DONE이 1회만 남는다.

`retry-until-dead` 성공 조건:

- k6 HTTP failure rate 0%
- retry enqueue와 retry depth가 먼저 증가한다.
- max retry 이후 `auth_error_dlq_total`이 증가한다.
- `dead_letter_message.reason_code`가 `max_retries` 계열로 기록된다.
- main queue, retry queue, Outbox backlog가 drain된다.
- DLQ 전환 대상이 아닌 메시지는 정상 consume success로 수렴한다.

## Failure Interpretation

- retry enqueue가 증가하지 않는다: failure injection 설정이 앱에 적용되지 않았거나 consumer 경로를 타지 않았다.
- retry depth가 계속 남는다: retry TTL/routing 또는 retry publish worker 문제를 의심한다.
- `retry-once`에서 DLQ가 증가한다: retry budget, fail-until 설정, idempotency 상태 전환을 확인한다.
- `retry-until-dead`에서 DLQ가 증가하지 않는다: max retry 정책, DLQ routing, DLQ consumer/원장 기록을 확인한다.
- Outbox backlog가 남는다: publish 경로 문제와 consumer retry pressure가 섞였을 수 있다.

## Evidence

### Retry pressure and recovery: `LT-004C-2026-07-13_183611`

조건:

- Mode: `retry-once`
- Target: 20 RPS, 6분
- Failure injection: recorded consumer failure 20%, `fail-until-retry-count=1`

결과:

- k6 HTTP requests: 7,201
- HTTP failure rate: 0%
- retry enqueue RPS avg: 3.512
- retry pressure ratio max: 23.126%
- Rabbit retry depth max: 28
- DLQ RPS max: 0
- Rabbit DLQ depth max: 0
- post-run drain: PASS, 30초

최종 DB 원장:

```text
outbox_message:
  auth.error.recorded.v1           PUBLISHED 7201
  auth.error.analysis.requested.v1 PUBLISHED 7201

processed_message:
  DONE 14402

dead_letter_message:
  count 0

auth_error:
  count 7201
```

판정:

- retry pressure가 의도대로 발생했다.
- retry 대상 메시지는 DLQ 없이 최종 성공으로 수렴했다.
- 자동 report verdict는 `FAIL`이지만, 이는 공통 acceptance가 retry pressure 자체를 일반 장애로 보기 때문이다. 이 실행에서는 retry pressure가 의도된 주입 신호다.

### Retry until DLQ: `LT-004C-2026-07-13_185310`

조건:

- Mode: `retry-until-dead`
- Target: 10 RPS, 4분
- Failure injection: recorded consumer failure 5%
- Drain option: `AllowedDlqDepth=100000`

결과:

- k6 HTTP requests: 2,400
- HTTP failure rate: 0%
- retry enqueue RPS avg: 1.809
- retry pressure ratio max: 31.25%
- Rabbit retry depth max: 76
- DLQ RPS max: 0.836
- Rabbit DLQ depth max: 0
- post-run drain: PASS, 151초
- post-run retry queue: 71 -> 0

최종 DB 원장:

```text
outbox_message:
  auth.error.recorded.v1           PUBLISHED 2400
  auth.error.analysis.requested.v1 PUBLISHED 2282

processed_message:
  DONE 4564
  DEAD 118

dead_letter_message:
  RETRY_EXHAUSTED 118

dead_letter_message by retry_count:
  auth.error.recorded.v1 RETRY_EXHAUSTED retry_count=5 count=118

auth_error:
  count 2400
```

판정:

- retry queue pressure가 의도대로 발생했다.
- 선택된 메시지는 max retry 소진 후 `RETRY_EXHAUSTED` reason code로 DLQ 원장에 기록됐다.
- 5% 주입 기준 기대값은 약 120건이고 실제 DLQ 원장 수는 118건으로 자연스러운 범위다.
- DLQ queue depth가 0인 것은 DLQ consumer가 메시지를 ACK하고 `dead_letter_message` 원장에 기록하는 구조 때문이며, 이 시나리오의 source of truth는 `auth_error_dlq_total`과 DB 원장이다.
- 자동 report verdict는 `FAIL`이지만, 이는 공통 acceptance가 retry pressure와 publish/consume mismatch를 일반 장애로 보기 때문이다. 이 실행에서는 retry pressure와 DLQ 전환이 의도된 결과다.

## Conclusion

LT-004C는 #57의 retry/DLQ pressure acceptance를 충족한다.

- retry pressure 재현 입력이 명시됐다.
- retry enqueue rate와 retry queue depth가 snapshot/report에 반영됐다.
- max retry 도달 시 DLQ 전환이 확인됐다.
- DLQ rate와 `dead_letter_message.reason_code` 원장 근거가 기록됐다.
- retry 중복 delivery는 `processed_message.outbox_id` 원장으로 DONE 또는 DEAD 단일 최종 상태에 수렴했다.
- drain verification은 retry 대기 메시지와 의도된 DLQ 원장 기록을 구분해서 해석했다.
