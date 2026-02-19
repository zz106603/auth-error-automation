# LT-001 Baseline 결과

## 1. 테스트 개요

- 목적: 시스템의 정상 상태(Baseline) 성능 및 병목 여부 확인
- 시나리오: LT-001 (Constant Arrival Rate)
- 실행 방식: Docker k6 → Local Spring
- DB / MQ: Docker (PostgreSQL, RabbitMQ)
- 실행 일시: 2026-02-19

---

## 2. k6 실행 설정

- Executor: constant-arrival-rate
- Rate: 5 requests/sec
- Duration: 3 minutes
- 총 요청 수: 900
- VUs: preAllocated 20 / max 50

---

## 3. k6 결과 요약

### 요청 성공률

- checks_succeeded: 100% (900 / 900)
- http_req_failed: 0%
- status is 2xx: 100%

### HTTP Latency

- avg: 12.13ms
- p90: 15.48ms
- p95: 17.83ms
- max: 287.95ms

→ HTTP 레벨에서 병목 없음.

---

## 4. DB 상태 (HikariCP)

테스트 종료 직후 측정:

- connections.active: 0
- connections.pending: 0
- connections.idle: 8

해석:

- pending=0 → 커넥션 풀 부족 현상 없음
- idle 여유 존재 → DB 병목 없음

---

## 5. DB 적재 확인

- auth_error 테이블 row 증가 확인
- 요청 수와 동일하게 데이터 적재됨

→ Ingest → DB Write 정상 동작

---

## 6. RabbitMQ 상태

### Queue 상태 (테스트 중 관측)

- Ready: 0 유지
- Unacked: 0 또는 순간적 소량 후 0 복귀
- Publish ≈ Deliver ≈ Consumer Ack (동일 수준)

해석:

- 메시지 적체 없음
- Consumer 처리 속도가 Publish 속도를 충분히 따라감
- Redelivered 거의 없음

→ MQ 병목 없음

---

## 7. Baseline 결론

5 RPS 환경에서 시스템은 다음 조건을 만족함:

- HTTP 처리 안정
- DB 커넥션 풀 여유
- DB 적재 정상
- MQ 적체 없음
- End-to-End 파이프라인 정상

### Baseline 기준선

- HTTP p95 ≈ 18ms
- DB pending = 0
- MQ Ready = 0 유지
- Publish ≈ Deliver ≈ Ack

---

## 8. 다음 단계

LT-002 Ramp-up 테스트를 통해:

- 어느 지점에서 Ready가 증가하기 시작하는지
- 어느 지점에서 Hikari pending이 발생하는지
- Outbox age가 증가하는 시점은 언제인지

병목 시작 지점을 탐지한다.
