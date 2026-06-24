# auth-error-automation Runbook

## DLQ Ledger

DLQ에 도착한 메시지는 `dead_letter_message` 원장에 기록된 뒤 ACK된다.

운영 확인 기준:

- `dead_letter_message.reason_code`로 DLQ 원인을 확인한다.
- `payload_hash`, `payload_size_bytes`, `outbox_id`, `event_type`을 기준으로 메시지를 식별한다.
- 같은 메시지가 반복 도착하면 새 row를 만들지 않고 `delivery_count`, `last_seen_at`이 증가한다.
- `payload` 원문은 DB 원장에만 보관하고 로그에는 남기지 않는다.
- `replay_status`는 현재 운영 판단 상태만 나타낸다. Replay API나 replay worker는 아직 없다.

장애 대응 기준:

- DLQ consumer가 원장 저장에 실패하면 RabbitMQ ACK를 하지 않는다.
- 이 경우 DLQ 메시지는 consumer 재시작 또는 채널 복구 후 다시 delivery될 수 있다.
- `reason_code = UNKNOWN` 비율이 증가하면 consumer 분류 로직 또는 신규 실패 유형을 점검한다.
