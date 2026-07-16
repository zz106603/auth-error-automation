# DM-001 Domain Mix Demo

DM-001은 capacity test가 아니다. 목적은 #62 taxonomy, #63 입력 모델, #64 diagnostic read model이 실제 다양한 인증 실패 payload를 받아 의미 있는 통계를 만들 수 있는지 확인하는 것이다.

## 목적

- 여러 인증 실패 유형이 `auth_error.error_type`에 분산 저장되는지 확인한다.
- provider, clientType, endpoint, principalHash, ipHash가 read model에서 집계 가능한지 확인한다.
- 정상 파이프라인을 통과하면서 Outbox/Consumer/DLQ 오분류가 없는지 smoke 기준으로 본다.
- MCP/Claude 자연어 질의에 사용할 demo 데이터를 만든다.

## Payload Mix

기본 분포:

| Error type | 비율 | Provider | Client type | Endpoint |
| --- | ---: | --- | --- | --- |
| `INVALID_CREDENTIALS` | 60% | `INTERNAL_AUTH` | `WEB` | `/api/login` |
| `TOKEN_EXPIRED` | 15% | `INTERNAL_AUTH` | `WEB` | `/api/token/refresh` |
| `TOKEN_INVALID_SIGNATURE` | 10% | `INTERNAL_AUTH` | `API` | `/api/token/validate` |
| `ACCOUNT_LOCKED` | 5% | `INTERNAL_AUTH` | `WEB` | `/api/login` |
| `AUTH_PROVIDER_TIMEOUT` | 5% | `OAUTH_PROVIDER` | `MOBILE` | `/api/mobile/login` |
| `UNKNOWN_AUTH_ERROR` | 5% | `GATEWAY` | `API` | `/api/auth/callback` |

`principalHash`와 `ipHash`는 k6에서 SHA-256 hex로 생성한다. `userId`, `clientIp`, `sessionId`는 기존 API 호환 필드로만 남기고, 통계/read model 기준은 hash 필드다.

## 실행 명령

```powershell
.\k6\script\run-dm-001-domain-mix.ps1 -ResetStateBeforeRun
```

빠른 smoke:

```powershell
.\k6\script\run-dm-001-domain-mix.ps1 -ResetStateBeforeRun -TargetRps 3 -DemoDuration 2m
```

기본값:

- `TargetRps`: 5
- `DemoDuration`: 5m
- `Scenario`: `DM-001`
- `TestId`: `DM-001-<yyyy-MM-dd_HHmmss>`

## 성공 기준

DM-001은 LT-002/LT-003처럼 knee나 steady capacity를 판단하지 않는다. 성공 기준은 아래 smoke 조건이다.

- k6 HTTP 요청이 2xx로 성공한다.
- post-run drain이 성공한다.
- DLQ depth가 0으로 유지된다.
- `auth_error_hourly_type_stats`에 payload mix의 error type들이 기록된다.
- `auth_error_context_distribution`에서 provider/clientType/endpoint 분포를 확인할 수 있다.
- `auth_error_cluster_summary`에서 errorType/provider/stackHash 기준 cluster 후보를 확인할 수 있다.

## 확인 SQL

최근 1시간 error type 분포:

```sql
select error_type, sum(error_count) as count
from auth_error_hourly_type_stats
where bucket_hour >= date_trunc('hour', now() - interval '1 hour')
group by error_type
order by count desc;
```

provider/clientType 분포:

```sql
select provider, client_type, error_type, sum(error_count) as count
from auth_error_context_distribution
where bucket_hour >= date_trunc('hour', now() - interval '1 hour')
group by provider, client_type, error_type
order by count desc;
```

DLQ reason 확인:

```sql
select reason_code, replay_status, sum(message_count) as count
from dead_letter_reason_summary
where bucket_hour >= date_trunc('hour', now() - interval '1 hour')
group by reason_code, replay_status
order by count desc;
```

## 해석

- `INVALID_CREDENTIALS`가 가장 많은 것은 정상 demo 분포다.
- `TOKEN_INVALID_SIGNATURE`는 security signal이므로 MCP 응답 예시에서 보안 이벤트 후보로 설명할 수 있다.
- `AUTH_PROVIDER_TIMEOUT`은 retryable 분석 속성이지만, 메시징 retry 정책을 자동 변경하지 않는다.
- `UNKNOWN_AUTH_ERROR`는 taxonomy 보강 필요성을 설명하기 위한 demo bucket이다.
- DLQ가 증가하면 domain-mix 성공이 아니라 API 계약 또는 pipeline 처리 문제로 본다.
