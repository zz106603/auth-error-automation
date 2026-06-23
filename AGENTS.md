1. 모든 응답은 한국어로 작성한다.
2. 작업 전 docs, README, 설정 파일을 먼저 확인한다.
3. 모르는 구조는 추정하지 않는다.
4. 요청 범위를 벗어난 수정은 하지 않는다.
5. 불필요한 리팩토링과 임시 우회 코드는 금지한다.
6. 이 프로젝트는 기능보다 운영 안정성과 장애 대응 역량을 보여주는 것이 목적이다.
7. Reliability > Observability > Backpressure > Scalability 순서로 판단한다.
8. Outbox, RabbitMQ, Retry, DLQ 변경 시 메시지 유실·중복·순서 문제를 반드시 검토한다.
9. 성능은 API latency만 보지 않고 E2E latency, backlog age, throughput, queue depth 기준으로 판단한다.
10. 작업 완료 후 변경 내용, 이유, 검증 결과, 남은 리스크를 요약한다.
