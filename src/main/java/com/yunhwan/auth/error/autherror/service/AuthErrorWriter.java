package com.yunhwan.auth.error.autherror.service;

import com.yunhwan.auth.error.autherror.dto.AuthErrorRecordedPayload;
import com.yunhwan.auth.error.autherror.dto.AuthErrorWriteResult;
import com.yunhwan.auth.error.autherror.repository.AuthErrorRepository;
import com.yunhwan.auth.error.domain.auth.AuthError;
import com.yunhwan.auth.error.domain.outbox.OutboxMessage;
import com.yunhwan.auth.error.outbox.dto.OutboxEnqueueCommand;
import com.yunhwan.auth.error.outbox.service.OutboxWriter;
import com.yunhwan.auth.error.outbox.support.api.IdempotencyKeyResolver;
import com.yunhwan.auth.error.outbox.support.api.OutboxEventDescriptor;
import com.yunhwan.auth.error.outbox.support.api.OutboxPayloadSerializer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthErrorWriter {

    private final AuthErrorRepository authErrorRepository;
    private final OutboxWriter outboxWriter;

    private final OutboxPayloadSerializer outboxPayloadSerializer;
    private final IdempotencyKeyResolver<AuthError> idempotencyKeyResolver;
    private final OutboxEventDescriptor authErrorRecordedEventDescriptor;

    /**
     * 한 트랜잭션으로:
     * 1) auth_error INSERT (id 생성)
     * 2) outbox_message UPSERT/RETURNING (멱등 보장)
     */
    @Transactional
    public AuthErrorWriteResult record(AuthError authError) {
        // 1) auth_error 저장
        AuthError saved = authErrorRepository.save(authError);

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
