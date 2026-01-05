package com.yunhwan.auth.error.outbox.service;

import com.yunhwan.auth.error.domain.outbox.OutboxMessage;
import com.yunhwan.auth.error.outbox.persistence.OutboxMessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.InetAddress;
import java.util.List;

@Service
@RequiredArgsConstructor
public class OutboxPoller {

    private final OutboxMessageRepository outboxMessageRepo;

    private final OutboxClaimer outboxClaimer;

    @Value("${outbox.poller.batch-size:50}")
    private int batchSize;

    private final String owner = resolveOwner();

    /** 한 번 돌 때: PENDING -> PROCESSING으로 "claim"만 한다 */
    public List<OutboxMessage> pollOnce() {
        return outboxClaimer.claim(batchSize, owner);
    }

    private static String resolveOwner() {
        // 운영: POD_NAME 같은 env를 우선 쓰는 식으로 확장 가능
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown";
        }
    }
}