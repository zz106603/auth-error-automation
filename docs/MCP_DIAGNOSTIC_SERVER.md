# MCP Diagnostic Server

이 문서는 #66 read-only MCP diagnostic server의 실행 방식, tool schema, 응답 정책을 정의한다.

서버는 Spring Boot `3.5.9`와 호환되는 Spring AI `1.1.8` MCP server starter를 사용한다. tool은 `@McpTool`, 입력은 `@McpToolParam`으로 선언하며 annotation scanner가 schema와 tool specification을 생성한다. 하위 MCP Java SDK가 JSON-RPC 처리, MCP 초기화, protocol version 협상, stdio transport를 담당하고 애플리케이션 코드는 read-only tool과 조회 로직만 제공한다.

## 1. 실행 모델

MCP 서버는 메인 auth-error-automation Spring Boot 애플리케이션에 포함하지 않는다. 같은 repository 안의 `mcp-diagnostic` Spring Boot Gradle 모듈로 두고, 별도 프로세스로 실행한다.

```text
Auth Error Automation
API -> AuthError + Outbox -> RabbitMQ -> Consumer -> Retry/DLQ

MCP Diagnostic Server
Claude -> MCP stdio tool -> PostgreSQL read-only views
```

이 구조에서 MCP 서버 장애는 API, Outbox, Consumer, Retry/DLQ 처리 경로에 영향을 주지 않는다.

SDK는 지원 protocol version을 client와 협상하므로 애플리케이션 코드에서 특정 MCP protocol version을 고정하지 않는다.

Spring Boot banner는 끄고 모든 애플리케이션 로그를 stderr로 보낸다. stdout은 MCP JSON-RPC frame 전용이며 일반 로그를 출력하지 않는다.

## 2. Read-only 원칙

- MCP 서버는 write/replay/operator action tool을 제공하지 않는다.
- DB 연결은 `readOnly=true`와 `set transaction read only`로 실행한다.
- 운영환경에서는 `MCP_DB_USERNAME`에 read-only DB 계정을 지정한다.
- connect/query timeout과 동시 조회 상한으로 진단 서버가 운영 DB를 무제한 점유하지 않게 한다.
- 응답에는 payload 원문, credential, token, raw userId, raw IP를 포함하지 않는다.
- 자유 형식 예외 문자열인 Outbox `last_error`는 반환하지 않고 오류 존재 여부만 반환한다.
- `payload_hash`, `principal_hash`, `ip_hash`, `stack_hash`, count, reason code, timestamp 중심으로 반환한다.

## 3. 설정

기본 설정은 기존 로컬 `.env`와 같은 DB env를 재사용할 수 있다.

```powershell
.\gradlew.bat :mcp-diagnostic:installDist

$env:DB_HOST = "localhost"
$env:DB_PORT = "5432"
$env:DB_NAME = "auth_pipeline"
$env:DB_USERNAME = "manager"
$env:DB_PASSWORD = "manager0"
.\mcp-diagnostic\build\install\mcp-diagnostic\bin\mcp-diagnostic.bat
```

read-only 계정을 쓰는 경우 MCP 전용 env가 우선된다.

```powershell
.\gradlew.bat :mcp-diagnostic:installDist

$env:MCP_DB_URL = "jdbc:postgresql://localhost:5432/auth_pipeline"
$env:MCP_DB_USERNAME = "auth_mcp_readonly"
$env:MCP_DB_PASSWORD = "<password>"
$env:MCP_DB_CONNECT_TIMEOUT_SECONDS = "3"
$env:MCP_DB_QUERY_TIMEOUT_SECONDS = "5"
$env:MCP_DB_MAX_CONCURRENT_QUERIES = "2"
.\mcp-diagnostic\build\install\mcp-diagnostic\bin\mcp-diagnostic.bat
```

세 보호 설정의 기본값은 각각 3초, 5초, 동시 2개다. 동시 한도를 넘은 요청은 대기열에 쌓지 않고 재시도 가능한 tool 오류로 거절한다. `get_incident_snapshot`은 5개 조회를 순차 실행하며 하나라도 실패하거나 timeout이면 부분 snapshot을 반환하지 않는다.

운영 전용 계정은 최소한 아래 기준으로 검증한다. `default_transaction_read_only`는 애플리케이션의 transaction 설정을 보완하는 방어선이며, table/view에는 `SELECT`만 부여한다.

```sql
alter role auth_mcp_readonly set default_transaction_read_only = on;
grant usage on schema public to auth_mcp_readonly;
grant select on auth_error, outbox_message, retry_publish_request, dead_letter_message to auth_mcp_readonly;
grant select on auth_error_hourly_type_stats, auth_error_context_distribution,
    auth_error_cluster_summary, retry_publish_request_summary, dead_letter_reason_summary to auth_mcp_readonly;

set role auth_mcp_readonly;
show transaction_read_only;
select has_table_privilege(current_user, 'auth_error', 'SELECT');
select has_table_privilege(current_user, 'auth_error', 'INSERT,UPDATE,DELETE');
reset role;
```

마지막 권한 확인은 `SELECT=true`, `INSERT,UPDATE,DELETE=false`여야 한다.

## 4. Tools

| Tool | 주요 입력 | 응답 |
| --- | --- | --- |
| `get_auth_error_summary` | `hoursBack`, `provider`, `errorType` | 총량, 유형별 count, security signal count |
| `get_auth_error_trend` | `hoursBack`, `provider`, `errorType`, `limit` | 시간 bucket별 type count |
| `get_top_auth_error_types` | `hoursBack`, `provider`, `limit` | type/provider/client/endpoint별 상위 count |
| `get_auth_error_clusters` | `hoursBack`, `provider`, `errorType`, `limit` | stackHash cluster 후보 |
| `get_dlq_summary` | `hoursBack`, `limit` | reason/replay status별 DLQ count |
| `get_retry_summary` | `hoursBack`, `limit` | retry publish request 상태별 count |
| `get_incident_snapshot` | `hoursBack`, `provider`, `errorType` | summary/top/cluster/retry/DLQ 통합 snapshot |
| `trace_auth_error` | `requestId`, `traceId`, `outboxId` | AuthError, Outbox, DLQ 원장 흐름 |

`hoursBack=N`은 호출 시점 `now()`부터 정확히 N시간 전까지의 반개방 범위 `[now()-N hours, query now]`다. trend 응답의 `bucket_hour`는 이 범위에 들어온 row를 PostgreSQL 세션 시간대 기준 정각으로 묶은 표시 단위이므로, 첫 bucket은 일부 시간만 포함될 수 있다. provider/errorType을 받는 summary, trend, top, cluster와 incident snapshot의 해당 세 영역에는 동일한 필터와 시간 범위를 적용한다. retry/DLQ에는 해당 차원이 없어 시간 범위만 동일하게 적용한다.

## 5. 예시 질문

- 지난 1시간 동안 인증 실패 유형별 분포를 보여줘.
- `TOKEN_INVALID_SIGNATURE`가 특정 provider에서 증가했는지 확인해줘.
- 지금 DLQ reason code별 현황을 요약해줘.
- retry publish request가 쌓이고 있는지 알려줘.
- 현재 incident snapshot을 보고 원인 후보를 정리해줘.

질문별 tool 매핑, 기대 답변, Runbook 연결과 replay/데이터 변경 경계는 [Claude MCP 운영 진단 가이드](MCP_CLAUDE_DIAGNOSTIC_GUIDE.md)를 따른다.

## 6. 예시 MCP 응답

`get_auth_error_summary`는 아래 형태의 JSON text content를 반환한다.

```json
{
  "windowHours": 1,
  "totalErrorCount": 1500,
  "securitySignalCount": 156,
  "byType": [
    {
      "error_type": "INVALID_CREDENTIALS",
      "auth_failure_severity": "LOW",
      "auth_failure_retryable": false,
      "auth_failure_security_signal": false,
      "error_count": 891
    }
  ]
}
```

## 7. 검증

tool schema와 등록 목록은 Spring AI annotation scanner가 생성한 결과를 기준으로 테스트한다. 별도 SDK client가 Spring Boot stdio server를 실행해 초기화, protocol version 협상, `tools/list`, read-only tool hint까지 확인한다. Testcontainers PostgreSQL 테스트는 provider 필터, 정확한 cluster 기간, retry/DLQ 집계와 trace 응답 정책을 실제 SQL로 검증한다.

```powershell
.\gradlew.bat :mcp-diagnostic:test
.\gradlew.bat :mcp-diagnostic:installDist
```

실제 client 연결은 Claude Desktop/Codex 같은 MCP client에서 위 실행 파일을 stdio server로 등록한 뒤 `tools/list`와 대표 조회 tool을 호출해 확인한다. stdin에 JSON-RPC를 직접 작성하는 방식은 SDK가 담당하는 초기화 및 protocol negotiation을 우회하므로 smoke 방식으로 사용하지 않는다.

## 8. 범위 밖

- DLQ replay 실행
- AuthError/Outbox/Retry/DLQ 상태 변경
- 운영 조치 자동화
- payload 원문 반환
