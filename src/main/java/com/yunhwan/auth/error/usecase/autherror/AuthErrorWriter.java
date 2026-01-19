package com.yunhwan.auth.error.usecase.autherror;

import com.yunhwan.auth.error.domain.autherror.AuthError;
import com.yunhwan.auth.error.domain.outbox.OutboxMessage;
import com.yunhwan.auth.error.domain.outbox.descriptor.OutboxEventDescriptor;
import com.yunhwan.auth.error.domain.outbox.policy.IdempotencyKeyResolver;
import com.yunhwan.auth.error.domain.outbox.policy.PayloadSerializer;
import com.yunhwan.auth.error.usecase.autherror.config.AuthErrorProperties;
import com.yunhwan.auth.error.usecase.autherror.dto.AuthErrorRecordedPayload;
import com.yunhwan.auth.error.usecase.autherror.dto.AuthErrorWriteResult;
import com.yunhwan.auth.error.usecase.autherror.port.AuthErrorStore;
import com.yunhwan.auth.error.usecase.outbox.OutboxWriter;
import com.yunhwan.auth.error.usecase.outbox.dto.OutboxEnqueueCommand;
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

    private final PayloadSerializer outboxPayloadSerializer;
    private final IdempotencyKeyResolver<AuthError> idempotencyKeyResolver;
    private final OutboxEventDescriptor authErrorRecordedEventDescriptor;

    /**
     * 한 트랜잭션으로:
     * 1) auth_error INSERT (id 생성)
     * 2) outbox_message UPSERT/RETURNING (멱등 보장)
     */
    @Transactional
    public AuthErrorWriteResult record(String requestId, OffsetDateTime occurredAt) {
        // 1) auth_error 저장

        OffsetDateTime now = OffsetDateTime.now(clock);
        AuthError toSave = AuthError.record(
                requestId,
                occurredAt,
                now,
                authErrorProperties.getSourceService(),
                authErrorProperties.getEnvironment()
        );

        AuthError saved = authErrorStore.save(toSave);

        // 2) outbox payload 최소 계약 (DLQ/추적에 유리)
        String payloadJson = outboxPayloadSerializer.serialize(new AuthErrorRecordedPayload(
                saved.getId(),
                saved.getRequestId(),
                saved.getOccurredAt()
        ));

        log.info(
                "[AuthErrorWriter] authError saved. id={}, payload={}",
                saved.getId(),
                payloadJson
        );

        // 3) outbox enqueue (기존 writer 계약 그대로)
        OutboxEnqueueCommand cmd = new OutboxEnqueueCommand(
                authErrorRecordedEventDescriptor.aggregateType(),
                String.valueOf(saved.getId()),     // aggregateId = authErrorId (추천)
                authErrorRecordedEventDescriptor.eventType(),
                payloadJson,
                idempotencyKeyResolver.resolve(saved)       // requestId/dedupKey 우선
        );

        OutboxMessage outbox = outboxWriter.enqueue(cmd);

        log.info(
                "[AuthErrorWriter] outbox created. outboxId={}, aggregateId={}, payload={}",
                outbox.getId(),
                outbox.getAggregateId(),
                outbox.getPayload()
        );

        return new AuthErrorWriteResult(saved.getId(), outbox.getId());
    }
}
