# Auth Failure Taxonomy

이 문서는 auth-error-automation의 인증 실패 이벤트 분류 기준이다.

본 프로젝트는 실제 로그인, JWT 발급/검증, OAuth provider 연동을 구현하는 인증 서비스가 아니다. 인증 실패 이벤트를 안정적으로 수집하고, 운영자가 원인과 영향 범위를 빠르게 판단할 수 있도록 표준 taxonomy와 집계 기준을 제공하는 운영 분석 시스템이다.

## 1. 목적

Auth failure taxonomy는 다음 후속 작업의 기준이다.

- AuthError 입력 모델 확장
- reason/type 기반 통계 view
- domain-mix k6 demo payload
- read-only MCP diagnostic tool schema
- Claude 자연어 진단 응답과 Runbook 연결

Taxonomy는 producer가 보낸 임의 문자열을 그대로 신뢰하지 않는다. 입력 값은 허용 목록으로 정규화하고, 알 수 없는 값은 `UNKNOWN_AUTH_ERROR`로 분류한다.

## 2. 기본 필드 후보

아래 필드는 #63에서 입력 모델과 DB migration으로 반영할 후보이며, #62 단계에서는 정책 기준만 정의한다.

| Field | 목적 | 원문 보관 정책 |
| --- | --- | --- |
| `errorType` | 인증 실패의 표준 유형 | enum 허용 목록만 저장 |
| `provider` | 실패가 발생한 인증 provider 또는 내부 auth module | 허용 목록 또는 짧은 normalized string |
| `clientType` | web, mobile, api, batch 등 client 분류 | enum/normalized string |
| `endpoint` | 실패가 발생한 API endpoint | route pattern 중심 저장 |
| `principalHash` | 사용자/계정 식별 집계 | 원문 금지, hash만 저장 |
| `ipHash` | IP 기반 급증/공격 징후 집계 | 원문 금지, hash 또는 prefix 정책 필요 |
| `userAgentFamily` | user-agent 계열 집계 | 원문 금지, family 수준만 저장 |

`userId`, `sessionId`, raw IP, raw user-agent, token, credential, authorization header는 일반 로그와 MCP 응답에 포함하지 않는다.

## 3. Error Type 기준

| Error type | Severity | Retryable | Security signal | Operator action | Cluster key 후보 |
| --- | --- | --- | --- | --- | --- |
| `INVALID_CREDENTIALS` | LOW | no | conditional | 특정 principal/client/IP에서 급증하는지 본다. 일반 사용자 입력 오류는 집계만 한다. | `errorType + provider + clientType` |
| `TOKEN_EXPIRED` | LOW | no | no | 만료 토큰 재사용 패턴, client refresh 흐름 문제를 본다. | `errorType + provider + clientType` |
| `TOKEN_INVALID_SIGNATURE` | HIGH | no | yes | key rotation, signing key 설정, 토큰 변조 가능성을 우선 확인한다. | `errorType + provider + stackHash` |
| `ACCOUNT_LOCKED` | MEDIUM | no | conditional | 계정 잠금 정책 변경, brute-force 징후, 특정 principal 집중 여부를 본다. | `errorType + provider + principalHash` |
| `MFA_FAILED` | MEDIUM | no | conditional | MFA provider 장애, 사용자 실패 급증, 특정 clientType 집중 여부를 본다. | `errorType + provider + clientType` |
| `RATE_LIMITED` | MEDIUM | no | yes | 공격성 traffic, rate-limit threshold 변경, 특정 ipHash 집중 여부를 본다. | `errorType + provider + ipHash` |
| `AUTH_PROVIDER_TIMEOUT` | HIGH | yes | no | 외부 provider latency/timeout, network, circuit breaker 상태를 본다. | `errorType + provider + stackHash` |
| `AUTH_PROVIDER_5XX` | HIGH | yes | no | provider 장애, dependency incident, retry/backoff 영향을 본다. | `errorType + provider + stackHash` |
| `UNKNOWN_AUTH_ERROR` | MEDIUM | no | conditional | 신규 분류 필요 여부를 판단한다. UNKNOWN 비율이 높으면 taxonomy를 보강한다. | `errorType + provider + stackHash` |

## 4. Severity 기준

| Severity | 의미 | 예시 |
| --- | --- | --- |
| LOW | 정상 사용자 행동 또는 낮은 운영 위험의 실패 | `INVALID_CREDENTIALS`, `TOKEN_EXPIRED` |
| MEDIUM | 운영자가 추세를 확인해야 하는 실패 | `ACCOUNT_LOCKED`, `MFA_FAILED`, `RATE_LIMITED`, `UNKNOWN_AUTH_ERROR` |
| HIGH | 장애 또는 보안 이벤트 후보로 즉시 확인해야 하는 실패 | `TOKEN_INVALID_SIGNATURE`, `AUTH_PROVIDER_TIMEOUT`, `AUTH_PROVIDER_5XX` |

Severity는 alert 조건 자체가 아니다. Alert는 발생량, 급증률, provider/client 집중도, DLQ/retry 동반 여부와 함께 판단한다.

## 5. Retryable 기준

Retryable은 인증 요청을 재시도하라는 뜻이 아니라, 실패 원인이 일시 장애 후보인지 표시하는 분석 속성이다.

- `AUTH_PROVIDER_TIMEOUT`, `AUTH_PROVIDER_5XX`는 dependency 장애가 해소되면 감소할 수 있으므로 retryable 후보로 본다.
- credential, token, contract 문제는 동일 입력을 다시 처리해도 성공하지 않으므로 retryable로 보지 않는다.
- Retryable type이라도 Outbox/Consumer retry 정책을 자동으로 변경하지 않는다. 메시징 retry는 기존 Retry/DLQ 정책을 따른다.

## 6. Security Signal 기준

Security signal은 보안 이벤트 후보로 우선 검토할지 나타낸다.

- `TOKEN_INVALID_SIGNATURE`, `RATE_LIMITED`는 기본 security signal이다.
- `INVALID_CREDENTIALS`, `ACCOUNT_LOCKED`, `MFA_FAILED`, `UNKNOWN_AUTH_ERROR`는 특정 principalHash/ipHash/clientType에 집중되거나 급증할 때 security signal로 승격한다.
- `TOKEN_EXPIRED`, provider timeout/5xx는 기본적으로 보안 신호가 아니라 client 또는 dependency 신호로 본다.

## 7. Cluster 기준

현재 구현은 `exceptionClass + stacktrace top lines` 기반 `stack_hash`를 사용한다. #63 이후 도메인 taxonomy가 모델에 반영되면 cluster key는 `stackHash` 단독보다 auth failure context를 포함해야 한다.

권장 우선순위:

1. `errorType + provider + stackHash`
2. stacktrace가 없거나 안정적이지 않으면 `errorType + provider + clientType`
3. security 집중도를 볼 때만 `principalHash` 또는 `ipHash`를 보조 차원으로 사용

원칙:

- requestId는 cluster key에 사용하지 않는다.
- 원문 userId, sessionId, raw IP, raw user-agent는 cluster key에 사용하지 않는다.
- principalHash/ipHash는 운영 영향 범위 분석용이며, 일반 cluster 폭발을 막기 위해 기본 key에는 넣지 않는다.

## 8. MCP 자연어 진단 질문 후보

이 taxonomy는 read-only MCP diagnostic server에서 다음 질문에 답하기 위한 기준이다.

- 지난 1시간 동안 가장 많이 발생한 인증 실패 유형은?
- `TOKEN_INVALID_SIGNATURE`가 특정 provider에서 급증했는가?
- `AUTH_PROVIDER_TIMEOUT`과 retry/DLQ 증가가 같은 시간대에 발생했는가?
- `RATE_LIMITED`가 특정 ipHash나 clientType에 집중되는가?
- `UNKNOWN_AUTH_ERROR` 비율이 taxonomy 보강이 필요할 정도로 높은가?

MCP 응답은 payload 원문, credential, token, raw userId, raw IP를 반환하지 않는다.

## 9. 범위 밖

아래 작업은 이 taxonomy의 범위가 아니다.

- 실제 로그인 구현
- JWT 발급/검증 전체 구현
- OAuth provider 연동
- 사용자 계정/권한 관리 시스템
- 자동 보안 차단 정책
- 자동 DLQ replay 또는 운영 조치 실행
