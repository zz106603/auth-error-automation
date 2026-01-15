package com.yunhwan.auth.error.usecase.outbox;

import com.yunhwan.auth.error.domain.outbox.OutboxMessage;
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
    private final Clock clock;

    /**
     * outbox_message에 메시지를 적재한다.
     *
     * 멱등성 보장 방식:
     * - 애플리케이션 로직이 아닌, DB 레벨에서 보장
     * - ON CONFLICT ... RETURNING
     */
    @Transactional
    public OutboxMessage enqueue(OutboxEnqueueCommand cmd) {
        return outboxMessageStore.upsertReturning(
                cmd.aggregateType(),
                cmd.aggregateId(),
                cmd.eventType(),
                cmd.payloadJson(),
                cmd.idempotencyKey(),
                OffsetDateTime.now(clock)
        );
    }

    /**
     * "새로 적재했는지 여부"만 필요한 경우 사용.
     */
    @Transactional
    public boolean tryEnqueue(OutboxEnqueueCommand cmd) {
        OutboxMessage message = OutboxMessage.pending(
                cmd.aggregateType(),
                cmd.aggregateId(),
                cmd.eventType(),
                cmd.payloadJson(),
                cmd.idempotencyKey()
        );

        try {
            outboxMessageStore.save(message);
            return true;
        } catch (DataIntegrityViolationException e) {
            return false;
        }
    }
}
