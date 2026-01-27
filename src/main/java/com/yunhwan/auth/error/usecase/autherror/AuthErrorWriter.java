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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthErrorWriter {

    private final AuthErrorStore authErrorStore;
    private final OutboxWriter outboxWriter;
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
    }
}
