# Architecture Boundaries

본 프로젝트는 헥사고날 아키텍처를 변경 전파를 제어하기 위한 운영 안정성 장치로 사용한다.

## Dependency Rules

- `domain`: 외부 계층에 의존하지 않는다.
- `usecase`: `domain`, usecase DTO, usecase port에만 의존한다.
- `usecase.port`: 외부 저장소, 메시징, 로깅, 분석기, 관측 구현을 향한 추상 계약이다.
- `infra`: usecase port를 구현하며 DB, RabbitMQ, Jackson, logger, metrics 같은 기술 세부사항을 담당한다.
- `app`: HTTP/controller DTO를 usecase command/result로 변환하고 usecase를 호출한다.

## Forbidden Directions

- `domain -> usecase/app/infra`
- `usecase -> app`
- `usecase -> infra`

## Operational Rationale

Outbox, Consumer, Retry, DLQ 정책은 usecase 계층의 핵심 안정성 로직이다. API DTO, RabbitMQ/Jackson 구현, 로그/지표 형식 변경은 이 정책 로직으로 전파되지 않아야 한다.
