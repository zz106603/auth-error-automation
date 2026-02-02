# Auth Error Outbox Pipeline

인증(Auth) 과정에서 발생하는 오류 이벤트를  
**Transactional Outbox Pattern** 기반으로 유실 없이 수집하고,  
비동기 처리 파이프라인(Retry/DLQ 포함)으로 안정적으로 전파/처리하며,  
`stack_hash` 기반 **Cluster(오류 군집)** 로 집계하여 모니터링 가능한 상태로 만드는 백엔드 인프라 프로젝트입니다.

> AI 요약/추천 기능은 본 프로젝트의 코어가 아니라 **Cluster를 입력 단위로 결합 가능한 확장 포인트(Future Extension)** 로만 남겨둡니다.

---

## Project Goal

이 프로젝트의 목표는 단순 메시지 발행이 아니라,

- 인증 오류 이벤트를 **유실 없이 수집** (Outbox)
- 장애 상황에서도 **재시도/격리 가능한 상태로 관리** (TTL Retry / DLQ)
- 중복 처리 없이 **멱등성(Idempotency) 보장**
- 누적된 오류를 `stack_hash` 기준으로 **Cluster 집계**하여
  - “같은 원인으로 보이는 오류 군”을 빠르게 파악
  - 대시보드/알림/운영 분석에 활용

할 수 있는 **신뢰 가능한 Error Ops Pipeline**을 구축하는 것입니다.

---

## Architecture Overview
![architecture.svg](docs/diagrams/architecture.svg)

### Components

- **Outbox Writer**: AuthError 저장과 이벤트 적재를 동일 트랜잭션으로 처리
- **Poller**: 처리 가능한 메시지를 claim (`FOR UPDATE SKIP LOCKED` 기반)
- **Processor/Consumer**: 메시지 처리 및 상태 전이
- **Reaper**: 장시간 PROCESSING 상태 메시지 회수
- **Retry Router**: TTL 기반 Retry Queue / DLQ 라우팅
- **Cluster Linker**: `stack_hash` 기반 Cluster upsert + link (집계 단위 생성)

---

## Message Lifecycle

| Status | Description |
| --- | --- |
| `PENDING` | 처리 대기 |
| `PROCESSING` | poller에 의해 claim |
| `PUBLISHED` | 정상 publish/처리 완료 |
| `DEAD` | 재시도 초과(DLQ) |

---

## State Transition Diagram
![outbox-state.svg](docs/diagrams/outbox-state.svg)

---

## Cluster Model (AuthError Aggregation)

이 프로젝트는 분석(analysis) 완료된 AuthError를 `stack_hash` 기준으로 Cluster에 연결합니다.

- **Cluster**: `cluster_key = stack_hash`
- **ClusterItem**: `cluster_id ↔ auth_error_id` 매핑(중복 link 방지)
- **Cluster count / last_seen**: 신규 link일 때만 count 증가, 중복에는 안전하게 touch만 수행

> Cluster는 “운영자가 판단/조치”를 강제하지 않고,  
> 모니터링 및 추후 AI 결합을 위한 **집계 단위(Read Model) 기반**으로만 유지합니다.

---

## Package Structure

헥사고날 아키텍처(Hexagonal Architecture)를 기반으로 **도메인(Domain)**, **유스케이스(UseCase)**, **인프라(Infra)** 계층을 명확히 분리했습니다.

```text
com.yunhwan.auth.error
├─ app                  # [Web Adapter] 애플리케이션 진입점
│  ├─ api
│  │  └─ auth
│  └─ autherror
├─ common               # [Shared] 공통 예외 및 유틸리티
│  └─ exception
├─ domain               # [Core Domain] 핵심 비즈니스 로직 (외부 의존성 없음)
│  ├─ autherror
│  ├─ consumer
│  └─ outbox
│     ├─ decision
│     ├─ descriptor
│     └─ policy
├─ usecase              # [Input Port] 애플리케이션 유스케이스
│  ├─ autherror
│  │  ├─ dto
│  │  └─ port
│  ├─ consumer
│  │  ├─ handler
│  │  ├─ observer
│  │  └─ port
│  └─ outbox
│  │  ├─ config
│  │  ├─ dto
│  │  └─ port
└─ infra                # [Output Port Adapter] 외부 시스템 연동 구현체
   ├─ autherror
   ├─ messaging         # RabbitMQ 메시징
   │  ├─ consumer
   │  └─ rabbit
   ├─ outbox            # Outbox 인프라 지원
   │  ├─ descriptor
   │  ├─ policy
   │  ├─ serializer
   │  └─ support
   ├─ persistence       # 데이터베이스 영속성 (JPA)
   │  ├─ adapter
   │  ├─ config
   │  └─ jpa
   ├─ scheduling
   └─ support
```

---

## Reliability Strategy

- **Transactional Outbox**로 이벤트 유실 방지
- **Idempotency Key** 기반 중복 처리 방지
- TTL 기반 **Retry Queue / DLQ**로 실패 격리
- **Reaper**로 stuck(PROCESSING) 메시지 회수
- 상태 기반(State Machine) 전이로 처리 흐름을 명확히 유지

---

## Testing Strategy

- **PostgreSQL(Testcontainers)** 기반 통합 테스트
- Poller / Consumer / Retry / DLQ / Reaper 시나리오 검증
- 의도적으로 실패를 주입하여 재시도/격리/멱등 처리의 정합성 확인

---

## Tech Stack

- Java 21
- Spring Boot
- Spring Data JPA
- PostgreSQL
- RabbitMQ
- Testcontainers
- JUnit 5

---

## Current Status

- [x]  Outbox 테이블 및 상태 전이
- [x]  Poller / Processor / Reaper 구성
- [x]  TTL 기반 Retry / DLQ 처리
- [x]  Idempotency 기반 중복 방지
- [x]  DB 기반 통합 테스트
- [x]  stack_hash 기반 Cluster upsert + link
- [ ]  RabbitMQ 실제 운영 설정(성능/튜닝) 고도화
- [ ]  ELK(또는 대체) 대시보드/지표 완성
- [ ]  부하 테스트(ingest/publish/consumer failure rate/dlq) 및 병목 분석

---

## Future Extension (AI-ready)
본 프로젝트는 AI를 코어로 두지 않지만,
Cluster는 “동일 원인군”을 안정적으로 묶어주는 단위이므로,
향후 다음과 같은 기능을 **옵션으로 안전하게 결합**할 수 있습니다.

- Cluster 요약/원인 추정
- 빈도/영향도 기반 자동 리포팅
- 운영자 승인 기반 추천 액션(추후 필요 시)

---

## Why This Matters

이 프로젝트는

**이벤트를 보내는 시스템**이 아니라

**운영 중 발생하는 오류를 자산으로 만드는 시스템**을 목표로 합니다.

Outbox 패턴을 통해 수집된 인증 오류 이벤트는

향후 AI 분석을 통해 **장애 패턴 요약, 빈도 분석, 자동 리포팅**으로 확장될 수 있습니다.

---

- Postgres: localhost:5432
- Rabbit UI: localhost:15672
- Actuator: localhost:8080/actuator/health