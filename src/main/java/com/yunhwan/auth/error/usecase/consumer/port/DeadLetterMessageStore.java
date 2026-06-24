package com.yunhwan.auth.error.usecase.consumer.port;

import com.yunhwan.auth.error.domain.consumer.DeadLetterMessage;
import com.yunhwan.auth.error.usecase.consumer.dto.DeadLetterMessageRecordCommand;

import java.util.Optional;

public interface DeadLetterMessageStore {

    DeadLetterMessage upsert(DeadLetterMessageRecordCommand command);

    Optional<DeadLetterMessage> findByDedupeKey(String dedupeKey);

    long count();

    void deleteAll();
}
