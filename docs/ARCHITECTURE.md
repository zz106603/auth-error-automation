# Architecture Boundaries

본 프로젝트는 헥사고날 아키텍처를 변경 전파를 제어하기 위한 운영 안정성 장치로 사용한다.

## Dependency Rules

- `domain`: 현재 프로젝트에서는 핵심 상태 전이와 JPA entity를 함께 둔다. 순수 도메인 객체만 있는 구조가 아니라, 운영 안정성 검증을 빠르게 하기 위한 실용적 구조다.
- `usecase`: `domain`, usecase DTO, usecase port에만 의존한다.
- `usecase.port`: 외부 저장소, 메시징, 로깅, 분석기, 관측 구현을 향한 추상 계약이다.
- `infra`: usecase port를 구현하며 DB, RabbitMQ, Jackson, logger, metrics 같은 기술 세부사항을 담당한다.
- `app`: HTTP/controller DTO를 usecase command/result로 변환하고 usecase를 호출한다.

## Current Domain Model Note

`domain` 패키지의 `AuthError`, `OutboxMessage`, `ProcessedMessage`, `RetryPublishRequest`, `DeadLetterMessage` 등은 JPA annotation을 가진 영속 엔티티다. 따라서 `domain`은 "외부 의존성이 전혀 없는 순수 모델"이 아니라, 상태 전이 규칙과 persistence mapping이 함께 있는 현재 구현의 중심 모델이다.

이 선택은 현재 프로젝트의 목표가 프레임워크 독립성보다 Outbox/RabbitMQ/Retry/DLQ 신뢰성 검증과 운영 증거 확보에 있기 때문이다. 추후 persistence entity와 pure domain model을 분리할 수는 있지만, 그 작업은 메시지 유실·중복·순서 리스크를 낮추는 직접 효과가 있을 때만 수행한다.

## Forbidden Directions

- `domain -> usecase/app/infra`
- `usecase -> app`
- `usecase -> infra`

## Operational Rationale

Outbox, Consumer, Retry, DLQ 정책은 usecase 계층의 핵심 안정성 로직이다. API DTO, RabbitMQ/Jackson 구현, 로그/지표 형식 변경은 이 정책 로직으로 전파되지 않아야 한다.
