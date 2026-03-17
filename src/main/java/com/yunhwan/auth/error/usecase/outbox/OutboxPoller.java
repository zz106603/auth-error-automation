package com.yunhwan.auth.error.usecase.outbox;

import com.yunhwan.auth.error.domain.outbox.OutboxMessage;
import com.yunhwan.auth.error.infra.metrics.MetricsConfig;
import com.yunhwan.auth.error.usecase.outbox.config.OutboxProperties;
import com.yunhwan.auth.error.usecase.outbox.dto.OutboxClaimResult;
import com.yunhwan.auth.error.usecase.outbox.port.OwnerResolver;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class OutboxPoller {

    private final OutboxClaimer outboxClaimer;
    private final OwnerResolver ownerResolver;
    private final OutboxProperties props;
    private final MeterRegistry meterRegistry;
    private final Timer outboxPollerClaimTimer;

    public OutboxPoller(
            OutboxClaimer outboxClaimer,
            OwnerResolver ownerResolver,
            OutboxProperties props,
            MeterRegistry meterRegistry
    ) {
        this.outboxClaimer = outboxClaimer;
        this.ownerResolver = ownerResolver;
        this.props = props;
        this.meterRegistry = meterRegistry;
        this.outboxPollerClaimTimer = Timer.builder(MetricsConfig.METRIC_OUTBOX_POLLER_CLAIM)
                .register(meterRegistry);
    }

    /** 한 번 돌 때: PENDING -> PROCESSING으로 "claim"만 한다 */
    public OutboxClaimResult pollOnce(String scopePrefixOrNull) {
        String owner = ownerResolver.resolve();
        int batchSize = props.getPoller().getBatchSize();

        Timer.Sample sample = Timer.start(meterRegistry);
        List<OutboxMessage> claimed;
        try {
            claimed = outboxClaimer.claimBatch(batchSize, owner, scopePrefixOrNull);
        } finally {
            sample.stop(outboxPollerClaimTimer);
        }
        return new OutboxClaimResult(owner, claimed);
    }
}
