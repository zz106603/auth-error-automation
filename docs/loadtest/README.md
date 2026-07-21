# 부하 테스트 문서와 증거

이 디렉터리는 표준 실행 방법, 결과 해석 기준, 시나리오별 보고서와 원본 증거를 함께 보존한다.

## 먼저 읽을 문서

1. [자동화 워크플로우](AUTOMATED_WORKFLOW.md): clean start, 고정 실행 구간, drain, snapshot과 report 생성
2. [결과 해석 가이드](RESULT_INTERPRETATION_GUIDE.md): E2E latency, backlog age, throughput, queue, Retry/DLQ 판정
3. 시나리오 문서: LT-001~LT-004D, DM-001의 목적과 결과

## 경로 책임

- `results/<test-id>/`: 최종 판정의 source of truth인 Prometheus snapshot과 summary
- `../../k6/results/<test-id>/`: k6 stdout, wrapper 상태, gate/drain artifact
- `ROADMAP.md`: 완료 증거가 아닌 후속 실험 계획과 상태 기록

API latency만 빠르다는 이유로 성공으로 판정하지 않는다. E2E latency, Outbox age, publish/consume 차이, queue depth, Retry/DLQ와 최종 drain을 함께 확인한다.
