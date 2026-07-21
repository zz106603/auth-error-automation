# Claude MCP 운영 진단 가이드

이 문서는 #67의 Claude 자연어 질문, MCP tool 호출, 응답 작성 기준, Runbook 연결을 정의한다. 목표는 Claude가 임의로 원인을 단정하는 것이 아니라 read-only MCP 결과를 근거로 관측 사실과 원인 후보를 구분해 설명하게 만드는 것이다.

MCP 서버 실행·보안 경계는 [MCP Diagnostic Server](MCP_DIAGNOSTIC_SERVER.md), 집계 의미는 [MCP Diagnostic Read Model](MCP_DIAGNOSTIC_READ_MODEL.md)을 따른다. Grafana의 latency/backlog/queue 시계열과 MCP의 원장 통계는 서로 보완하며 어느 한쪽만으로 원인을 확정하지 않는다.

## 1. 답변 원칙

Claude의 운영 진단 답변은 아래 순서를 따른다.

1. **조회 범위**: 시간 범위와 적용한 provider/errorType/식별자 필터를 밝힌다.
2. **관측 사실**: MCP가 반환한 count, status, reason code, hash, timestamp만 사실로 표현한다.
3. **원인 후보**: Runbook과 taxonomy에 근거한 가설임을 명시한다.
4. **미확인 사항**: MCP 데이터만으로 확인할 수 없는 metric, 배포, 설정 변경, 외부 provider 상태를 분리한다.
5. **다음 확인**: read-only 확인 절차를 우선 제안한다.
6. **조치 경계**: replay, row 수정/삭제, queue purge 같은 변경 작업은 실행하거나 무조건 권고하지 않는다.

숫자가 0이거나 결과가 없으면 정상이라고 단정하지 않는다. 조회 기간, 필터, read model 갱신 여부, DB 연결 대상을 먼저 확인한다.

## 2. 대표 질문과 Tool 매핑

| 자연어 질문 | 우선 Tool | 보조 Tool | 답변 기준 / Runbook |
| --- | --- | --- | --- |
| 지난 1시간 동안 인증 실패가 가장 많은 유형은? | `get_auth_error_summary(hoursBack=1)` | `get_top_auth_error_types(hoursBack=1)` | 총량과 type count를 제시하고 [빠른 판단표](RUNBOOK.md#빠른-판단표)로 다음 확인 위치를 안내한다. |
| `TOKEN_INVALID_SIGNATURE`가 갑자기 늘었어? | `get_auth_error_trend(hoursBack=6, errorType=TOKEN_INVALID_SIGNATURE)` | `get_auth_error_summary(hoursBack=6, errorType=TOKEN_INVALID_SIGNATURE)` | 시간 bucket 증가를 보여주고 security signal 후보로 분류한다. key rotation/signing key/토큰 변조 가능성은 가설로만 제시한다. |
| 특정 provider에서 `TOKEN_INVALID_SIGNATURE`가 늘었어? | `get_auth_error_trend(hoursBack=6, provider=..., errorType=TOKEN_INVALID_SIGNATURE)` | `get_auth_error_summary(hoursBack=6, provider=..., errorType=TOKEN_INVALID_SIGNATURE)` | 같은 provider/type 범위의 시간 bucket을 비교한다. 첫 bucket은 조회 시작 시각 이후의 일부 구간일 수 있으므로 급증 판단 시 이를 밝힌다. |
| provider timeout이 장애와 관련 있어? | `get_auth_error_trend(hoursBack=6, errorType=AUTH_PROVIDER_TIMEOUT)` | `get_retry_summary`, `get_dlq_summary` | 같은 시간대의 retry/DLQ 동반 여부를 제시하고 외부 provider latency/network/circuit breaker 확인을 권고한다. |
| 특정 `requestId`가 어디서 멈췄어? | `trace_auth_error(requestId=...)` | 필요 시 `traceId` 또는 `outboxId`로 재조회 | AuthError → Outbox → DLQ 원장 존재 여부와 마지막 상태를 보여주고 [먼저 5분 안에 확인할 것](RUNBOOK.md#먼저-5분-안에-확인할-것)에 연결한다. |
| 특정 `traceId` 또는 `outboxId` 흐름을 보여줘. | `trace_auth_error(traceId=...)` 또는 `trace_auth_error(outboxId=...)` | 없음 | payload 원문 없이 ID, status, reason, timestamp로 경로를 설명한다. row 상태 변경은 제안하지 않는다. |
| DLQ reason code별로 요약해줘. | `get_dlq_summary(hoursBack=1)` | `get_incident_snapshot(hoursBack=1)` | reason별 count/delivery/replay status를 제시하고 [DLQ 급증](RUNBOOK.md#dlq-급증), [Replay 운영 판단](RUNBOOK.md#dlq-replay-운영-판단)에 연결한다. |
| `PAYLOAD_INVALID_JSON`이 늘었는데 재처리할까? | `get_dlq_summary(hoursBack=1)` | 필요 시 `trace_auth_error(outboxId=...)` | producer 계약 위반 후보이며 replay 금지임을 명시한다. payload 원문 복사를 요구하지 않는다. |
| retry publish request가 쌓였어? | `get_retry_summary(hoursBack=1)` | `get_dlq_summary`, `get_incident_snapshot` | status별 request count와 publish retry count를 제시한다. `DEAD` 증가는 retry publish 경로 장애 후보로 본다. |
| 지금 전체 장애 상황을 요약해줘. | `get_incident_snapshot(hoursBack=1)` | 필요한 세부 tool | summary/top/cluster/retry/DLQ를 함께 보고 상관관계는 후보로 표현한다. metric 기반 확정은 Runbook의 Prometheus 확인이 필요하다. |
| 동일 원인 cluster가 있어? | `get_auth_error_clusters(hoursBack=1)` | `get_auth_error_summary` | `errorType + provider + stackHash`와 count를 제시한다. stacktrace 전문이나 개인정보 원문은 요청하지 않는다. |

## 3. 응답 형식

권장 형식은 다음과 같다.

```text
[조회 범위]
- 최근 1시간, provider 필터 없음

[관측 사실]
- 전체 인증 실패: 1,500건
- TOKEN_INVALID_SIGNATURE: 156건
- security signal: 156건

[원인 후보]
- TOKEN_INVALID_SIGNATURE는 taxonomy상 security signal이다.
- key rotation 또는 signing key 설정 불일치 후보를 우선 확인할 수 있다.
- 현재 결과만으로 토큰 변조나 침해를 확정할 수는 없다.

[미확인]
- 최근 key rotation/배포 여부
- provider별 시간 추이
- API latency, queue depth 같은 Prometheus 지표

[다음 확인]
- provider/client 분포와 stackHash cluster를 read-only로 추가 조회한다.
- Runbook의 최근 변경 및 추적 ID 확인 절차를 따른다.

[조치 경계]
- 데이터 수정, queue purge, replay는 수행하지 않는다.
```

## 4. 시나리오별 기대 답변 예시

아래 수치는 형식 설명을 위한 예시이며 실제 운영 결과가 아니다.

### 4.1 인증 실패 상위 유형

질문:

> 지난 1시간 동안 인증 실패가 가장 많은 유형은?

호출:

```text
get_auth_error_summary(hoursBack=1)
get_top_auth_error_types(hoursBack=1, limit=5)
```

기대 답변:

> 최근 1시간 인증 실패는 총 1,500건이며 `INVALID_CREDENTIALS`가 891건으로 가장 많습니다. `TOKEN_INVALID_SIGNATURE`는 156건이고 taxonomy상 security signal입니다. 이는 관측된 분포이며 장애 또는 공격을 단독으로 확정하지는 않습니다. provider/client 분포와 이전 시간 bucket을 추가로 확인하고, Runbook의 빠른 판단 순서에 따라 API 지연과 후단 backlog도 함께 확인해야 합니다.

### 4.2 TOKEN_INVALID_SIGNATURE 증가

질문:

> `TOKEN_INVALID_SIGNATURE`가 갑자기 늘었어?

호출:

```text
get_auth_error_trend(hoursBack=6, errorType="TOKEN_INVALID_SIGNATURE", limit=50)
get_auth_error_clusters(hoursBack=6, errorType="TOKEN_INVALID_SIGNATURE", limit=10)
```

기대 답변:

> 최근 6개 시간 bucket 중 직전 bucket 대비 count가 증가했습니다. 동일 `stackHash` cluster가 함께 커졌다면 공통 검증 경로나 설정 문제 후보가 됩니다. taxonomy 기준으로 key rotation, signing key 설정 불일치, 토큰 변조 가능성을 확인해야 하지만 현재 count와 hash만으로 원인을 확정할 수 없습니다. 최근 배포/key rotation과 provider 상태를 별도로 확인하세요.

`갑자기`라는 표현은 최소 두 개 이상의 시간 bucket 비교가 있을 때만 사용한다. provider별 시간 bucket이 없으면 특정 provider에서 증가했다고 표현하지 않는다.

### 4.3 Request 추적

질문:

> requestId `req-123`이 어디서 멈췄어?

호출:

```text
trace_auth_error(requestId="req-123")
```

기대 답변:

> `req-123`에 해당하는 AuthError와 Outbox 원장은 확인되지만 Outbox가 `PENDING` 상태이고 `published_at`은 없습니다. 현재 증거상 RabbitMQ 이전 발행 경로에서 대기 중인 후보입니다. 다만 MCP 결과만으로 Poller 중지 또는 broker 장애를 확정할 수 없으므로 Runbook의 Publish Silence, Outbox age, RabbitMQ 상태를 확인해야 합니다. Outbox row를 수동 변경하거나 삭제하지 마세요.

### 4.4 PAYLOAD_INVALID_JSON DLQ

질문:

> `PAYLOAD_INVALID_JSON` DLQ가 늘었는데 replay할까?

호출:

```text
get_dlq_summary(hoursBack=1, limit=20)
```

기대 답변:

> 최근 1시간 `PAYLOAD_INVALID_JSON`이 42건 관측됐습니다. Runbook상 producer 계약 위반 후보이며 replay 금지 대상입니다. 동일 payload를 재주입하면 같은 실패가 반복될 가능성이 높습니다. producer 메시지 생성/직렬화 경로를 먼저 수정하고 reason code, payload hash, 최초·최종 관측 시각으로 영향 범위를 확인하세요. payload 원문을 일반 로그나 답변에 복사하지 마세요.

### 4.5 RETRY_EXHAUSTED

질문:

> `RETRY_EXHAUSTED`가 생겼는데 다시 처리해도 돼?

기대 답변:

> `RETRY_EXHAUSTED`는 자동 replay 대상이 아니라 조건부 검토 후보입니다. 일시 장애 해소, 동일 business idempotency 기준의 중복 side effect 부재, operator 승인과 audit trail이 확인되기 전에는 재처리하면 안 됩니다. 현재 MCP 서버는 replay 기능을 제공하지 않습니다. 우선 retry/DLQ count와 관련 outbox 흐름을 read-only로 확인하세요.

## 5. Runbook 판단 연결

| MCP 신호 | 원인 후보 | Runbook에서 추가 확인 | 금지 또는 승인 필요 |
| --- | --- | --- | --- |
| `AUTH_PROVIDER_TIMEOUT`, `AUTH_PROVIDER_5XX` 증가 | 외부 dependency 지연/장애 | retry depth, DLQ, provider 상태, network/circuit breaker | 원인 확인 전 retry 설정을 임의 변경하지 않는다. |
| `TOKEN_INVALID_SIGNATURE` 증가 | signing key/rotation 설정 또는 보안 이벤트 후보 | 최근 배포·key rotation, provider/client/cluster | 침해로 단정하지 않고 credential/token 원문을 출력하지 않는다. |
| retry `DEAD` 증가 | retry publish 경로 terminal failure | `last_publish_error`, publish retry count, broker 상태 | processed ledger를 수동 수정하지 않는다. |
| `PAYLOAD_INVALID_JSON`, `PAYLOAD_MISSING_AUTH_ERROR_ID` | producer 계약 위반 | producer 직렬화/메시지 생성 경로 | replay 금지. |
| `DOMAIN_AUTH_ERROR_NOT_FOUND` | commit 순서/정합성/out-of-order 후보 | AuthError/Outbox 원장과 timestamp | replay 금지. |
| `RETRY_EXHAUSTED` | 일시 장애가 retry 한도를 초과 | 원인 해소, idempotency, side effect, audit 조건 | operator 승인 전 replay 금지. bulk replay 금지. |
| 동일 `payload_hash`의 delivery count 증가 | poison/반복 격리 후보 | reason code와 최초·최종 시각 | 원문 노출 및 무검증 재주입 금지. |

상세 절차는 [Runbook](RUNBOOK.md), 상태/Replay 정책은 [Policy](POLICY.md), 인증 실패 의미는 [Auth Failure Taxonomy](AUTH_FAILURE_TAXONOMY.md)를 단일 기준으로 사용한다.

## 6. Claude 연결 및 사용 예시

먼저 실행 배포본을 만든다.

```powershell
.\gradlew.bat :mcp-diagnostic:installDist
```

Claude의 MCP server 설정에는 생성된 실행 파일과 DB 환경변수를 등록한다. 아래 값은 예시이며 운영환경에서는 반드시 전용 read-only DB 계정을 사용한다.

```json
{
  "mcpServers": {
    "auth-error-diagnostic": {
      "command": "C:\\path\\to\\auth-error-automation\\mcp-diagnostic\\build\\install\\mcp-diagnostic\\bin\\mcp-diagnostic.bat",
      "env": {
        "MCP_DB_URL": "jdbc:postgresql://localhost:5432/auth_pipeline",
        "MCP_DB_USERNAME": "auth_mcp_readonly",
        "MCP_DB_PASSWORD": "<password>"
      }
    }
  }
}
```

연결 후 다음처럼 조회한다.

```text
최근 1시간 incident snapshot을 조회해줘.
관측 사실, 원인 후보, 미확인 사항, 다음 확인, 조치 경계 순서로 답하고
replay나 데이터 변경은 실행하거나 무조건 권고하지 마.
```

## 7. 현재 한계

- MCP read model은 DB 원장/집계 기준이며 Prometheus의 API latency, queue depth, publish silence를 직접 반환하지 않는다.
- `hoursBack`은 호출 시점 기준 정확한 기간이며 trend의 첫 정각 bucket은 일부 구간만 포함될 수 있다.
- incident snapshot의 인증 실패 summary/top/cluster에는 같은 provider/errorType/window가 적용되지만 retry/DLQ는 provider/errorType 차원이 없어 시간 범위만 공유한다.
- 서로 다른 집계 결과의 시간적 동시 발생은 원인 관계를 증명하지 않는다.
- MCP 응답은 운영 판단을 보조하며 incident 선언, 보안 침해 확정, replay 승인을 대신하지 않는다.
- write/replay/operator action은 제공하지 않는다.
