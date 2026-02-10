package com.yunhwan.auth.error.usecase.autherror;

import com.yunhwan.auth.error.domain.autherror.AuthError;
import com.yunhwan.auth.error.domain.outbox.OutboxMessage;
import com.yunhwan.auth.error.domain.outbox.descriptor.OutboxEventDescriptor;
import com.yunhwan.auth.error.infra.logging.AuthErrorEventLogger;
import com.yunhwan.auth.error.usecase.autherror.config.AuthErrorProperties;
import com.yunhwan.auth.error.usecase.autherror.dto.AuthErrorRecordedPayload;
import com.yunhwan.auth.error.usecase.autherror.dto.AuthErrorWriteCommand;
import com.yunhwan.auth.error.usecase.autherror.dto.AuthErrorWriteResult;
import com.yunhwan.auth.error.usecase.autherror.port.AuthErrorStore;
import com.yunhwan.auth.error.usecase.outbox.OutboxWriter;
import com.yunhwan.auth.error.usecase.outbox.port.OutboxMessageStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.SQLException;
import java.time.Clock;
import java.time.OffsetDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthErrorWriter {

    private final AuthErrorStore authErrorStore;
    private final OutboxWriter outboxWriter;
    private final OutboxMessageStore outboxMessageStore;
    private final Clock clock;
    private final AuthErrorProperties authErrorProperties;
    private final OutboxEventDescriptor<AuthErrorRecordedPayload> authErrorRecordedEventDescriptor;
    private final AuthErrorEventLogger eventLogger;

    /**
     * 한 트랜잭션으로:
     * 1) auth_error INSERT (id 생성)
     * 2) outbox_message UPSERT/RETURNING (멱등 보장)
     */
    @Transactional
    public AuthErrorWriteResult record(AuthErrorWriteCommand cmd) {
        String dedupKey = cmd.requestId();
        if (dedupKey != null) {
            var existing = authErrorStore.findByDedupKey(dedupKey);
            if (existing.isPresent()) {
                return buildExistingResult(existing.get());
            }
        }
        // 1) auth_error 저장

        OffsetDateTime now = OffsetDateTime.now(clock);
        AuthError toSave = AuthError.record(
                cmd.requestId(),
                cmd.occurredAt(),
                now,
                authErrorProperties.getSourceService(),
                authErrorProperties.getEnvironment()
        );


        // 요청 컨텍스트
        toSave.applyRequestContext(
                cmd.httpMethod(),
                cmd.requestUri(),
                cmd.clientIp(),
                cmd.userAgent(),
                cmd.userId(),
                cmd.sessionId()
        );

        // http_status 저장 + stack_hash 계산
        toSave.applyExceptionContext(
                cmd.exceptionClass(),
                cmd.exceptionMessage(),
                cmd.rootCauseClass(),
                cmd.rootCauseMessage(),
                cmd.stacktrace(),
                cmd.httpStatus()
        );

        try {
            AuthError saved = authErrorStore.save(toSave);

            // 2) outbox payload 최소 계약 (DLQ/추적에 유리)
            AuthErrorRecordedPayload payload = new AuthErrorRecordedPayload(
                    saved.getId(),
                    saved.getRequestId(),
                    saved.getOccurredAt()
            );

            OutboxMessage outbox = outboxWriter.enqueue(
                    authErrorRecordedEventDescriptor,
                    String.valueOf(saved.getId()), // aggregateId
                    payload
            );

            // idempotency_key를 descriptor에서 뽑아서 이벤트 로그에 포함
            String idemKey = authErrorRecordedEventDescriptor.idempotencyKey(payload);
            // 이벤트 로그
            eventLogger.recorded(saved, outbox.getId(), idemKey);

            return new AuthErrorWriteResult(saved.getId(), outbox.getId());
        } catch (DuplicateKeyException e) {
            return fetchExistingAfterConflict(dedupKey, e);
        }
    }

    private AuthErrorWriteResult fetchExistingAfterConflict(String dedupKey, RuntimeException cause) {
        AuthError existing = authErrorStore.findByDedupKey(dedupKey)
                .orElseThrow(() -> cause);
        return buildExistingResult(existing);
    }

    private AuthErrorWriteResult buildExistingResult(AuthError existing) {
        AuthErrorRecordedPayload payload = new AuthErrorRecordedPayload(
                existing.getId(),
                existing.getRequestId(),
                existing.getOccurredAt()
        );
        String idemKey = authErrorRecordedEventDescriptor.idempotencyKey(payload);
        OutboxMessage outbox = outboxMessageStore.findByIdempotencyKey(idemKey)
                .orElseThrow(() -> new IllegalStateException("recorded outbox missing for authErrorId=" + existing.getId()));
        return new AuthErrorWriteResult(existing.getId(), outbox.getId());
    }
}
