# Testing Baseline

이 문서는 로컬과 CI에서 테스트 실패가 코드 회귀인지 Docker/Testcontainers 환경 문제인지 구분하기 위한 기준선이다.

## Test Classes

- Quick tests: Docker가 없어도 실행 가능한 테스트. Gradle tag `integration`을 제외한다.
- Integration tests: PostgreSQL/RabbitMQ Testcontainers가 필요한 테스트. `AbstractIntegrationTest` 또는 `AbstractStubIntegrationTest`를 상속하며 tag `integration`을 가진다.
- Failure scenario tests: retry, DLQ, poison message, duplicate delivery 같은 장애 주입 테스트. 현재는 integration test 범주에 포함된다.
- Load tests: k6와 observability stack을 사용하는 별도 검증이다. `docs/LOAD_TEST_GUIDE.md`와 `docs/loadtest/AUTOMATED_WORKFLOW.md`를 따른다.

## Local Commands

빠른 코드 검증:

```powershell
.\gradlew.bat quickTest
```

Docker/Testcontainers 사전 점검:

```powershell
.\scripts\check-testcontainers.ps1
```

통합 검증:

```powershell
.\gradlew.bat integrationTest
```

특정 통합 테스트:

```powershell
.\gradlew.bat integrationTest --tests "com.yunhwan.auth.error.consumer.ConsumerPayloadPoisonDlqIntegrationTest"
```

## Failure Triage

- `quickTest` 실패: Docker와 무관한 코드/설정 회귀로 본다.
- `check-testcontainers.ps1` 실패: Docker daemon 또는 Docker Desktop 상태를 먼저 복구한다.
- `integrationTest`에서 `Could not find a valid Docker environment`: 코드 실패가 아니라 Testcontainers 환경 실패로 분류한다.
- `integrationTest`가 컨테이너 시작 이후 실패: DB/RabbitMQ 의존 동작 또는 테스트 코드 회귀로 본다.

## Phase 1 Reliability Regression Set

Phase 1 reliability 변경 전후 최소 회귀 검증은 다음 순서로 수행한다.

```powershell
.\gradlew.bat quickTest
.\scripts\check-testcontainers.ps1
.\gradlew.bat integrationTest --tests "com.yunhwan.auth.error.consumer.ConsumerContractViolationDlqIntegrationTest" --tests "com.yunhwan.auth.error.consumer.ConsumerPayloadPoisonDlqIntegrationTest"
.\gradlew.bat integrationTest --tests "com.yunhwan.auth.error.decision.DecisionGuardIntegrationTest"
```

전체 통합 검증은 시간이 더 걸리지만, merge 전 기준선으로 사용한다.

```powershell
.\gradlew.bat integrationTest
```
