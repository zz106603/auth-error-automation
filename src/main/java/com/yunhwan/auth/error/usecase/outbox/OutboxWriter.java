package com.yunhwan.auth.error.usecase.outbox;

import com.yunhwan.auth.error.domain.outbox.OutboxMessage;
import com.yunhwan.auth.error.domain.outbox.descriptor.OutboxEventDescriptor;
import com.yunhwan.auth.error.domain.outbox.policy.PayloadSerializer;
import com.yunhwan.auth.error.usecase.outbox.dto.OutboxEnqueueCommand;
import com.yunhwan.auth.error.usecase.outbox.port.OutboxMessageStore;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;

@Service
@RequiredArgsConstructor
public class OutboxWriter {

    private final OutboxMessageStore outboxMessageStore;
    private final PayloadSerializer payloadSerializer;
    private final Clock clock;

    /**
     * outbox_message에 메시지를 적재한다.
     *
     * 멱등성 보장 방식:
     * - 애플리케이션 로직이 아닌, DB 레벨에서 보장
     * - ON CONFLICT ... RETURNING
     */
    @Transactional
    public <T> OutboxMessage enqueue(OutboxEventDescriptor<T> descriptor, String aggregateId, T payload) {
        return enqueueInternal(new OutboxEnqueueCommand(
                descriptor.aggregateType(),
                aggregateId,
                descriptor.eventType(),
                payloadSerializer.serialize(payload),
                descriptor.idempotencyKey(payload)
        ));
    }

    private OutboxMessage enqueueInternal(OutboxEnqueueCommand cmd) {
        return outboxMessageStore.upsertReturning(
                cmd.aggregateType(),
                cmd.aggregateId(),
                cmd.eventType(),
                cmd.payloadJson(),
                cmd.idempotencyKey(),
                OffsetDateTime.now(clock)
        );
    }



}
