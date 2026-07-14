-- V10__harden_outbox_payload_hash.sql
-- payload_hash is the drift-detection key for outbox idempotency.
--
-- Do not backfill with payload::text here: PostgreSQL jsonb text rendering is
-- not guaranteed to match the Java PayloadSerializer bytes used by OutboxWriter.
-- If this guard fails in an existing environment, run an app-serializer based
-- backfill first, then re-run migration.

do $$
begin
    if exists (select 1 from outbox_message where payload_hash is null) then
        raise exception
            'outbox_message.payload_hash has NULL rows. Backfill with the application payload serializer before applying NOT NULL.';
    end if;
end $$;

alter table outbox_message
    alter column payload_hash set not null;

alter table outbox_message
    add constraint ck_outbox_payload_hash_sha256
    check (payload_hash ~ '^[0-9a-f]{64}$')
    not valid;

alter table outbox_message
    validate constraint ck_outbox_payload_hash_sha256;
