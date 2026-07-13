# LT-004B RabbitMQ Unavailable Failure Injection

## 목적

RabbitMQ가 일시적으로 사용 불가능할 때 API 요청이 DB와 Outbox에 안전하게 남고, RabbitMQ 복구 후 Outbox publish와 consumer 처리가 유실 없이 수렴하는지 확인한다.

이 시나리오는 RabbitMQ container를 테스트 중 의도적으로 중지했다가 다시 시작한다. 애플리케이션 로직이나 payload는 바꾸지 않는다.

## 사전 조건

- 앱은 local profile로 실행한다.
- #55 consumer delay 설정은 꺼야 한다.
- `auth_error_runtime_consumer_delay_recorded_ms`가 `0`이어야 한다.
- Docker compose의 RabbitMQ container 이름은 기본값 `auth_pipeline_rabbitmq`를 사용한다.

IntelliJ VM options 예시:

```text
-Dspring.profiles.active=local
```

## 실행

기본값은 30 RPS, 6분, k6 시작 60초 후 RabbitMQ 60초 중단이다.

```powershell
.\k6\script\run-lt-004-rabbitmq-unavailable.ps1 -ResetStateBeforeRun
```

필요하면 장애 주입 시간을 명시한다.

```powershell
.\k6\script\run-lt-004-rabbitmq-unavailable.ps1 -ResetStateBeforeRun -TargetRps 30 -SteadyDuration 6m -FaultStartDelaySec 60 -RabbitDownDurationSec 60
```

## 산출물

표준 workflow 산출물에 더해 아래 파일을 남긴다.

```text
k6/results/<test-id>/
  ├─ fault-injection.log
  ├─ fault-injection.status.json
  └─ fault-injection.job.log
```

`fault-injection.status.json`에는 k6 시작 감지 시각, RabbitMQ stop/start 시각, recovery 시각이 기록된다. 장애 주입이 실패하면 runner는 최종 exit code를 실패로 반환해야 한다.

## 성공적으로 테스트가 수행된 상태

이 장애 주입은 report의 `PASS`만으로 성공 여부를 판단하지 않는다. 아래 조건이 함께 확인되어야 한다.

- k6 HTTP 요청은 대부분 2xx로 완료된다.
- RabbitMQ down window 동안 publish success가 줄거나 멈추고, publish error/timeout 계열 metric이 증가한다.
- Outbox backlog count와 age가 증가한다.
- RabbitMQ 복구 후 Outbox backlog가 감소한다.
- 최종 publish/consume counter가 k6 요청 수와 수렴한다.
- retry queue와 DLQ depth는 증가하지 않아야 한다.
- post-run drain이 제한 시간 안에 끝난다.

## 판정

다음이면 의도대로 장애 복구가 관측된 것이다.

- RabbitMQ unavailable 기간에 Outbox가 메시지를 보존한다.
- 복구 후 Outbox publisher가 재시도하여 RabbitMQ publish가 재개된다.
- consumer가 최종적으로 모든 메시지를 처리한다.
- DLQ/retry queue로 오분류되지 않는다.

다음이면 다음 단계로 넘어가기 전에 원인을 확인한다.

- API 5xx가 증가한다: RabbitMQ 장애가 API write path를 침범한 것이다.
- Outbox backlog가 증가하지 않는다: 장애 주입이 publish 경로에 걸리지 않았거나 관측 query가 잘못되었다.
- 복구 후 drain이 실패한다: retry delay, poller 처리량, RabbitMQ recovery, connection 재연결 문제를 분리해야 한다.
- DLQ/retry queue가 증가한다: publish 경로 장애와 consumer 처리 실패가 섞였을 가능성이 있다.

## 실행 증거

### LT-004B-2026-07-13_175403

조건:

- Target RPS: 30
- Duration: 6m
- Fault start delay: 60s
- RabbitMQ down duration: 60s
- Runtime profile: local
- Outbox retry budget: LT-004B 검증용으로 `max-retries=20`, `delay-seconds=10` 적용

Fault window:

- k6 started detected: 2026-07-13T17:56:33+09:00
- RabbitMQ stop started: 2026-07-13T17:57:33+09:00
- RabbitMQ start started: 2026-07-13T17:58:36+09:00
- RabbitMQ healthy: 2026-07-13T17:58:42+09:00

결과:

- k6 HTTP requests: 10,801
- k6 HTTP failed rate: 0%
- Outbox backlog count max: 2,932
- Outbox age p95/p99 max: 77,589ms / 86,576ms
- Ingest-to-consume p95/p99 max: 89,935.72ms / 108,411.18ms
- Rabbit ready/unacked max: 170 / 100
- Retry queue depth: 0
- DLQ depth: 0
- Post-run drain time: 26s
- DB final state: `auth.error.recorded.v1` PUBLISHED 10,801, `auth.error.analysis.requested.v1` PUBLISHED 10,801
- DB final state: `processed_message` DONE 21,602
- DB final state: DEAD 0

판정:

- RabbitMQ unavailable 기간에 Outbox backlog와 age가 명확히 증가했다.
- API write path는 5xx 없이 AuthError/Outbox를 보존했다.
- RabbitMQ 복구 후 Outbox publish와 consumer 처리가 최종 수렴했다.
- retry queue와 DLQ로 오분류되지 않았고, DB 기준 DEAD 없이 종료했다.

주의:

- 자동 report verdict는 `FAIL`이다. 이는 LT-003 steady 기준의 E2E latency와 Outbox backlog growth check가 장애 주입 신호를 실패로 판정하기 때문이다. LT-004B에서는 이 증가가 기대 신호다.
- `post_run_counters_present`가 Prometheus post-run counter diff에서 `UNKNOWN`으로 기록될 수 있다. 최종 수렴 여부는 drain snapshot과 DB final state를 함께 확인한다.
- `LT-004B-2026-07-13_172743`에서는 기본 retry budget으로 `DEAD`가 발생했다. 60초 RabbitMQ 장애를 견디기 위한 운영 retry budget은 별도 정책 결정 대상으로 남긴다.
