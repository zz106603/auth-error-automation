package com.yunhwan.auth.error.usecase.consumer.port;

import com.yunhwan.auth.error.usecase.consumer.dto.DeadLetterSourceSnapshot;

public interface DeadLetterSourceLookup {

    DeadLetterSourceSnapshot findSnapshot(Long outboxId);
}
