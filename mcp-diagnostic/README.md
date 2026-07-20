# MCP Diagnostic Server

`mcp-diagnostic`은 auth-error-automation의 PostgreSQL read model을 조회하는 read-only MCP 서버다.

목표는 Claude 같은 MCP client가 자연어 질문으로 인증 실패 통계, retry 압력, DLQ reason 분포, incident snapshot을 조회하게 만드는 것이다. 이 서버는 write, replay, operator action을 제공하지 않는다.

별도 Spring Boot `3.5.9` 프로세스로 실행하며 Spring AI `1.1.8`의 MCP server starter와 annotation scanner를 사용한다. tool은 `@McpTool`/`@McpToolParam`으로 선언하고, 하위 MCP Java SDK가 protocol version 협상, JSON-RPC lifecycle, stdio transport를 담당한다.

## 실행

로컬 앱과 DB가 실행 중인 상태에서 별도 프로세스로 실행한다.

```powershell
.\gradlew.bat :mcp-diagnostic:installDist

$env:DB_HOST = "localhost"
$env:DB_PORT = "5432"
$env:DB_NAME = "auth_pipeline"
$env:DB_USERNAME = "manager"
$env:DB_PASSWORD = "manager0"
.\mcp-diagnostic\build\install\mcp-diagnostic\bin\mcp-diagnostic.bat
```

운영/데모 환경에서는 가능하면 아래처럼 read-only 계정을 별도로 지정한다.

```powershell
.\gradlew.bat :mcp-diagnostic:installDist

$env:MCP_DB_URL = "jdbc:postgresql://localhost:5432/auth_pipeline"
$env:MCP_DB_USERNAME = "auth_mcp_readonly"
$env:MCP_DB_PASSWORD = "<password>"
.\mcp-diagnostic\build\install\mcp-diagnostic\bin\mcp-diagnostic.bat
```

DB 조회는 JDBC connection `readOnly=true`와 PostgreSQL `set transaction read only`로 실행된다.

## Tools

| Tool | 목적 |
| --- | --- |
| `get_auth_error_summary` | 기간 내 인증 실패 총량, 유형별 분포, 보안 신호 합계 |
| `get_auth_error_trend` | 시간 bucket별 인증 실패 추이 |
| `get_top_auth_error_types` | provider/client/httpStatus/endpoint 기준 상위 실패 유형 |
| `get_auth_error_clusters` | errorType/provider/stackHash 기준 cluster 후보 |
| `get_dlq_summary` | DLQ reason/replay status 분포 |
| `get_retry_summary` | retry publish request 상태와 재발행 압력 |
| `get_incident_snapshot` | summary/top/cluster/retry/DLQ를 한 번에 조회 |
| `trace_auth_error` | `requestId`, `traceId`, `outboxId` 기반 read-only 추적 |

## Payload Policy

응답에는 payload 원문, credential, token, raw userId, raw IP를 포함하지 않는다. 반환 기준은 `errorType`, `provider`, `clientType`, `endpoint`, `reasonCode`, `replayStatus`, `payloadHash`, `principalHash`, `ipHash`, `stackHash`, count, timestamp다.

## 검증

annotation 기반 tool 등록과 schema를 테스트하고, SDK client ↔ Spring Boot stdio server 초기화·protocol negotiation·`tools/list`·read-only hint 통합 테스트를 수행한 뒤 실행 배포본을 생성한다.

```powershell
.\gradlew.bat :mcp-diagnostic:test
.\gradlew.bat :mcp-diagnostic:installDist
```

E2E smoke는 MCP client에 생성된 `mcp-diagnostic.bat`을 stdio server로 등록한 뒤 tool 목록과 대표 조회를 호출한다. raw JSON-RPC 입력은 초기화와 protocol negotiation을 우회하므로 지원하지 않는다.
