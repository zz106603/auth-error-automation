# Load Test Checklist (k6) — auth-error-automation

> 목적: k6로 트래픽을 만들기 전에, **관측 도구 / 수집 지표 / 중단 기준 / 테스트 시나리오 / 결과 기록 방식**을 최소 세트로 고정한다.  
> 범위: API → DB → Outbox → MQ(RabbitMQ) → Consumer → Retry/DLQ → Cluster/Decision 흐름.

---

## 0. 사전 원칙

- **k6 결과는 “API 응답 시간”만으로 판단하지 않는다.**
- 성공 기준은 **파이프라인(Outbox/MQ/Consumer)이 지속적으로 따라가며 backlog가 통제되는지**로 정의한다.
- 모든 테스트는 **테스트 ID(예: LT-001)** 로 기록하고 재현 가능해야 한다.
- **테스트 환경 값(스펙/버전/설정)** 을 반드시 함께 기록한다.

---

## 1. 테스트 환경(필수 기록)

### 1.1 실행 환경
- OS/Kernel:
- CPU / Memory:
- Java / Spring Boot 버전:
- PostgreSQL 버전:
- RabbitMQ 버전:
- Docker/Compose(사용 시) 버전:
- 배포 형태(Local/EC2/Docker/K8s):

### 1.2 애플리케이션 설정(핵심만)
- HikariCP: maxPoolSize / connectionTimeout / maxLifetime:
- Outbox poller 주기 / batch size:
- Consumer concurrency:
- Retry 정책(TTL ladder):
- DLQ 라우팅 규칙:

### 1.3 데이터/트래픽 전제
- 테스트 데이터 초기 상태(테이블 row 수):
- 요청 payload 크기(대략):
- 평균 stacktrace 길이 상한 적용 여부:

---

## 2. 관측(Observability) 최소 세팅

> “없으면 부하 테스트를 시작하지 않는다” 체크리스트

### 2.1 Spring Actuator / Micrometer
- [ ] `/actuator/health` 응답 OK
- [ ] `/actuator/metrics` 접근 가능
- [ ] (선택) `/actuator/prometheus` 노출

필수로 볼 메트릭(최소):
- HTTP 서버:
    - `http.server.requests` (count, p95/p99)
- JVM:
    - `jvm.memory.used`, `jvm.gc.pause`
- HikariCP:
    - `hikaricp.connections.active`
    - `hikaricp.connections.pending`
    - `hikaricp.connections.idle`

### 2.2 RabbitMQ Management
- [ ] Management UI 접속 가능 (예: `:15672`)
- [ ] Queue depth / rate 확인 가능

필수 관측 항목:
- Ready / Unacked
- Publish rate / Deliver rate
- Retry queue depth
- DLQ depth

### 2.3 PostgreSQL 관측
- [ ] slow query 로그 활성화(기준: 500ms 또는 1000ms)
- [ ] `pg_stat_activity`, `pg_locks` 조회 가능

필수 체크 쿼리(예시):
```sql
-- Outbox backlog
SELECT status, count(*) FROM outbox_event GROUP BY status;

-- Queue 성격의 테이블이 있다면 backlog 추적
-- 예: PENDING/READY/FAILED 등

-- Lock / wait 확인
SELECT wait_event_type, wait_event, count(*)
FROM pg_stat_activity
GROUP BY wait_event_type, wait_event
ORDER BY count(*) DESC;
```
### 2.4 애플리케이션 로그(최소)

- [ ]  요청 단위 correlation id / requestId 추적 가능
- [ ]  Consumer 실패 로그에서 retry/dlq 전환 원인이 식별 가능

---

## 3. 테스트 전 Baseline(필수)

> 부하 전 “기준선”을 확보해야, 부하 결과가 해석 가능하다.
>
- [ ]  단일 요청 1회 평균 응답시간(ms) 기록
- [ ]  1분간 소량 트래픽(예: 1~5 RPS)에서
    - [ ]  API p95
    - [ ]  Hikari pending=0 유지 여부
    - [ ]  MQ depth 0으로 회복 여부
    - [ ]  Outbox PENDING이 누적되지 않는지 확인

---

## 4. 중단 기준(Stop Conditions)

> 아래 중 하나라도 발생하면 즉시 중단하고 원인 분석 후 재시도
>
- [ ]  Hikari `connections.pending` 지속 증가(예: 30초 이상) + API timeout/5xx 발생
- [ ]  Outbox PENDING backlog가 계속 증가하며 회복되지 않음(예: 2분 이상)
- [ ]  RabbitMQ Unacked가 비정상적으로 누적 + consumer 처리율 저하 지속
- [ ]  DLQ가 급격히 증가(의도한 실패 시나리오가 아닌데 증가)
- [ ]  DB lock wait 증가 + slow query 급증 + p95 폭증

---

## 5. 테스트 시나리오(권장 최소 4종)

> 각 시나리오는 “목표 / 부하 모델 / 성공 기준 / 수집 지표 / 예상 병목”을 함께 기록한다.
>

### LT-001 Baseline Smoke (정상 흐름)

- 목표: 파이프라인이 backlog 없이 따라가는지 확인
- 부하 모델: 1~5 RPS, 2~5분
- 성공 기준:
    - Outbox backlog가 0으로 회복
    - MQ depth가 0 또는 안정 수준 유지
    - DLQ 증가 없음
- 수집: API p95, Hikari pending, Outbox pending count, queue depth

### LT-002 Ramp-up (점진 증가)

- 목표: 임계점(throughput limit) 찾기
- 부하 모델: 단계 증가 (예: 10 → 50 → 100 → 300 VUs), 각 1분
- 성공 기준:
    - 특정 구간까지 backlog가 통제됨
    - 임계점에서 어떤 레이어가 병목인지 식별 가능
- 수집: 위 항목 + DB slow query + consumer 처리율

### LT-003 Steady Load (지속 부하)

- 목표: 장시간 안정성 / 누수/적체 확인
- 부하 모델: 일정 부하(예: 100~200 VUs), 10~20분
- 성공 기준:
    - p95가 시간이 지날수록 악화되지 않음
    - backlog가 누적 추세가 아님
- 수집: JVM GC pause, memory, queue/outbox 추세

### LT-004 Failure Injection (의도적 실패)

- 목표: retry/dlq 정책이 설계대로 작동하는지 검증
- 부하 모델: 낮은~중간 부하 + 실패 조건(예: consumer failAlways / downstream error)
- 성공 기준:
    - retry queue로 이동/재시도 간격이 정책과 일치
    - 한계 초과 시 DLQ로 라우팅
    - DB 상태 전이가 정책대로 남음
- 수집: retry queue depth, dlq 증가율, 관련 엔티티 status 전이

---

## 6. 테스트 결과 기록 템플릿(필수)

> 각 테스트 실행 후 아래를 채워서 남긴다.
>

### 실행 요약

- Test ID:
- 실행 날짜/시간(Asia/Seoul):
- Git commit hash:
- 환경 요약:
- k6 스크립트/옵션:

### 결과(수치)

- API p95 / p99:
- error rate(4xx/5xx):
- Hikari pending max:
- Outbox PENDING max:
- MQ main queue depth max:
- Retry queue depth max:
- DLQ 증가량:
- DB slow query count:

### 해석(짧게)

- 병목 지점(추정):
- 근거(지표/로그):
- 다음 액션(설정 변경/코드 개선/재실행 계획):

---

## 7. 부하 테스트 준비 완료 체크(Go/No-Go)

- [ ]  Actuator / metrics 확인 완료
- [ ]  RabbitMQ UI에서 queue 상태 확인 가능
- [ ]  DB slow query / lock 관측 가능
- [ ]  Outbox backlog 쿼리 준비 완료
- [ ]  Stop Conditions 합의 완료
- [ ]  LT-001~LT-004 최소 1회 실행 계획 수립

> 위 항목이 모두 체크되면 k6 단계로 진행한다.
>