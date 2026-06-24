package com.yunhwan.auth.error.common.exception;

public class OutboxPayloadMismatchException extends RuntimeException {

    public OutboxPayloadMismatchException(String idempotencyKey) {
        super("outbox payload hash mismatch. idempotencyKey=" + idempotencyKey);
    }
}
