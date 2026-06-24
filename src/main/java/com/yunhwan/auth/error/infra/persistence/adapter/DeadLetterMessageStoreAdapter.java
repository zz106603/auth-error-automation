package com.yunhwan.auth.error.infra.persistence.adapter;

import com.yunhwan.auth.error.domain.consumer.DeadLetterMessage;
import com.yunhwan.auth.error.infra.persistence.jpa.DeadLetterMessageJpaRepository;
import com.yunhwan.auth.error.usecase.consumer.dto.DeadLetterMessageRecordCommand;
import com.yunhwan.auth.error.usecase.consumer.port.DeadLetterMessageStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Repository
@RequiredArgsConstructor
@Transactional
public class DeadLetterMessageStoreAdapter implements DeadLetterMessageStore {

    private final DeadLetterMessageJpaRepository repo;

    @Override
    public DeadLetterMessage upsert(DeadLetterMessageRecordCommand command) {
        return repo.upsert(
                command.dedupeKey(),
                command.dlqQueue(),
                command.sourceQueue(),
                command.sourceExchange(),
                command.sourceRoutingKey(),
                command.deadLetterExchange(),
                command.deadLetterRoutingKey(),
                command.outboxId(),
                command.eventType(),
                command.aggregateType(),
                command.payload(),
                command.payloadHash(),
                command.payloadSizeBytes(),
                command.reasonCode().name(),
                command.brokerDeathReason(),
                command.xDeath(),
                command.retryCount(),
                command.processedMessageStatusAtArrival(),
                command.outboxStatusAtArrival(),
                command.replayStatus().name(),
                command.now()
        );
    }

    @Override
    public Optional<DeadLetterMessage> findByDedupeKey(String dedupeKey) {
        return repo.findByDedupeKey(dedupeKey);
    }

    @Override
    public long count() {
        return repo.count();
    }

    @Override
    public void deleteAll() {
        repo.deleteAll();
    }
}
