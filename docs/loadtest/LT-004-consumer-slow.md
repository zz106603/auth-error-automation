# LT-004A Consumer Slow Failure Injection

## 목적

Consumer 처리 지연이 발생했을 때 API 2xx만으로 성공처럼 보이지 않고, E2E latency, publish/consume imbalance, RabbitMQ ready/unacked depth, drain 결과에서 압력이 드러나는지 확인한다.

이 시나리오는 recorded consumer가 `processed_message` claim에 성공한 뒤 handler를 호출하기 전에 고정 지연을 넣는다. payload, Outbox publish, retry/DLQ 결정 로직은 바꾸지 않는다.

## 앱 실행

테스트 전 앱을 지연 설정과 함께 다시 시작한다.

```powershell
.\gradlew.bat bootRun --args='--spring.profiles.active=local --auth-error.loadtest.consumer-delay.recorded-ms=150'
```

이미 환경 변수로 실행하는 방식이 편하면 아래처럼 설정해도 된다.

```powershell
$env:AUTH_ERROR_LOADTEST_CONSUMER_DELAY_RECORDED_MS = '150'
.\gradlew.bat bootRun --args='--spring.profiles.active=local'
```

runner는 시작 전에 아래 Prometheus metric이 기대값인지 확인한다.

```promql
auth_error_runtime_consumer_delay_recorded_ms
```

값이 `150`이 아니면 k6를 시작하지 않는다.

## 실행

기본값은 30 RPS, 10분, recorded consumer delay 150ms이다.

```powershell
.\k6\script\run-lt-004-consumer-slow.ps1 -ResetStateBeforeRun
```

필요하면 값을 명시한다.

```powershell
.\k6\script\run-lt-004-consumer-slow.ps1 -ResetStateBeforeRun -TargetRps 30 -SteadyDuration 10m -ExpectedConsumerDelayMs 150
```

## 성공적으로 테스트가 수행된 상태

이 장애 주입은 report의 `PASS`만으로 성공 여부를 판단하지 않는다. 아래 조건이 함께 확인되어야 한다.

- k6 HTTP 요청은 대부분 2xx로 완료된다.
- `auth_error_runtime_consumer_delay_recorded_ms`가 실행 중 기대값을 유지한다.
- publish count는 k6 요청 수와 맞고, consumer 처리량은 지연으로 인해 run window 안에서 뒤처질 수 있다.
- E2E latency p95/p99, RabbitMQ ready/unacked, post-run drain 시간이 LT-003 30 RPS steady보다 나빠진다.
- retry enqueue rate와 DLQ rate/depth는 0에 가까워야 한다. Consumer slow는 장애 격리나 재시도 폭증이 아니라 backpressure 관측 시나리오다.
- post-run drain이 제한 시간 안에 끝나면 지연 상황에서도 유실 없이 수렴 가능한 것으로 본다.

## 판정

다음이면 의도대로 압력이 관측된 것이다.

- E2E latency 또는 RabbitMQ depth가 LT-003 정상 기준선보다 명확히 증가한다.
- post-run counter delta 기준으로 publish/consume이 최종 수렴한다.
- DLQ/retry가 증가하지 않는다.

다음이면 다음 단계로 넘어가기 전에 원인을 확인한다.

- preflight metric이 0이거나 누락된다: 앱이 delay 설정으로 재시작되지 않았다.
- DLQ/retry가 증가한다: 단순 지연이 아니라 handler 실패 또는 retry 정책 문제가 섞였다.
- drain이 실패한다: 지연 강도, consumer concurrency/prefetch, 처리량 한계를 분리해서 다시 봐야 한다.

## 실행 증거

### LT-004A-2026-07-13_155542

조건:

- Target RPS: 30
- Duration: 10m
- Recorded consumer delay: 150ms
- Runtime profile: local
- Consumer concurrency/max-concurrency/prefetch: 4 / 4 / 25

결과:

- k6 HTTP requests: 18,000
- k6 HTTP failed rate: 0%
- Runtime delay metric: `auth_error_runtime_consumer_delay_recorded_ms = 150`
- Run-window publish/consume avg RPS: 28.793 / 22.542
- Rabbit ready max: 3,787
- Rabbit unacked max: 100
- Ingest-to-consume p95/p99 max: 136,293.63ms / 137,209.89ms
- Retry enqueue RPS: 0
- Retry queue depth: 0
- DLQ depth: 0
- Post-run drain time: 183s
- Final drain state: Rabbit ready 0, unacked 0, retry 0, DLQ 0, outbox backlog 0
- Drain-time actuator counter: publish 18,000, consume 18,000

판정:

- Consumer delay로 인한 backpressure가 의도대로 관측되었다.
- API 요청은 성공했지만 consumer 처리량이 publish보다 낮아져 RabbitMQ ready/unacked와 E2E latency가 증가했다.
- 지연은 retry/DLQ로 오분류되지 않았다.
- k6 종료 후 drain이 제한 시간 안에 완료되어 메시지 유실 없이 최종 수렴했다.

주의:

- 자동 report verdict는 `FAIL`이다. 이는 LT-003 steady 기준의 baseline-relative E2E, publish/consume mismatch, Rabbit depth check가 실패했기 때문이며, LT-004A 장애 주입 목적에는 부합한다.
- `post_run_counters_present`는 Prometheus post-run counter diff에서 publish/consume delta가 `UNKNOWN`으로 기록되었다. 다만 `post_run_drain.actuator.prom` 기준 최종 publish/consume counter가 각각 18,000으로 확인되어 실제 미처리나 유실로 보지는 않는다.
