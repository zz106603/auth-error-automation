-- V9__add_outbox_payload_hash.sql
-- Store a stable hash for the serialized outbox payload so idempotency-key
-- conflicts can reject payload drift instead of silently returning the old row.

alter table outbox_message
    add column if not exists payload_hash varchar(64);

