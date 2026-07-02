package com.yunhwan.auth.error.infra.messaging.consumer.listener;

import com.yunhwan.auth.error.domain.consumer.ProcessedStatus;
import com.yunhwan.auth.error.domain.outbox.decision.OutboxDecision;
import com.yunhwan.auth.error.usecase.consumer.ConsumerDecisionMaker;
import com.yunhwan.auth.error.usecase.consumer.ConsumerRetryRequestRecorder;
import com.yunhwan.auth.error.usecase.consumer.port.ProcessedMessageStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("ConsumerFlowSupport 상태 전이 회귀 테스트")
class ConsumerFlowSupportTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final OffsetDateTime NOW = OffsetDateTime.now(CLOCK);
    private static final String QUEUE = "auth.error.recorded.q";
    private static final String EVENT_TYPE = "auth.error.recorded.v1";
    private static final String AGGREGATE_TYPE = "AUTH_ERROR";
    private static final String PAYLOAD = "{\"authErrorId\":1}";

    @Test
    @DisplayName("claim 실패는 현재 terminal 상태를 돌려주어 duplicate ACK(drop) 판단을 유지한다")
    void claim_returns_existing_status_when_not_claimed() {
        ProcessedMessageStore store = mock(ProcessedMessageStore.class);
        ConsumerFlowSupport support = support(store, mock(ConsumerDecisionMaker.class), mock(ConsumerRetryRequestRecorder.class));

        when(store.claimProcessingUpdate(eq(100L), eq(NOW), eq(NOW.plusSeconds(60)))).thenReturn(0);
        when(store.findStatusByOutboxId(100L)).thenReturn(Optional.of(ProcessedStatus.DONE));

        ConsumerFlowSupport.ClaimResult result = support.claim(100L, NOW);

        assertThat(result.claimed()).isFalse();
        assertThat(result.status()).isEqualTo(ProcessedStatus.DONE);
        verify(store).ensureRowExists(100L, NOW);
    }

    @Test
    @DisplayName("retry 결정은 RetryPublishRequest만 기록하고 processed_message DEAD로 만들지 않는다")
    void retry_failure_records_retry_request_only() {
        ProcessedMessageStore store = mock(ProcessedMessageStore.class);
        ConsumerDecisionMaker decisionMaker = mock(ConsumerDecisionMaker.class);
        ConsumerRetryRequestRecorder retryRecorder = mock(ConsumerRetryRequestRecorder.class);
        ConsumerFlowSupport support = support(store, decisionMaker, retryRecorder);
        RuntimeException failure = new RuntimeException("temporary failure");
        OutboxDecision decision = OutboxDecision.ofRetry(1, NOW.plusSeconds(5), "temporary failure");

        when(decisionMaker.decide(eq(NOW), eq(0), eq(failure))).thenReturn(decision);

        ConsumerFlowSupport.FailureResult result = support.handleFailure(
                QUEUE,
                EVENT_TYPE,
                AGGREGATE_TYPE,
                PAYLOAD,
                101L,
                message(),
                NOW,
                failure
        );

        assertThat(result.dead()).isFalse();
        verify(retryRecorder).recordRetryRequest(101L, EVENT_TYPE, AGGREGATE_TYPE, PAYLOAD, NOW, decision);
        verify(store, never()).markDead(anyLong(), any(), any());
    }

    @Test
    @DisplayName("dead 결정은 processed_message를 DEAD로 확정하고 retry request를 만들지 않는다")
    void dead_failure_marks_dead_only() {
        ProcessedMessageStore store = mock(ProcessedMessageStore.class);
        ConsumerDecisionMaker decisionMaker = mock(ConsumerDecisionMaker.class);
        ConsumerRetryRequestRecorder retryRecorder = mock(ConsumerRetryRequestRecorder.class);
        ConsumerFlowSupport support = support(store, decisionMaker, retryRecorder);
        RuntimeException failure = new RuntimeException("terminal failure");
        OutboxDecision decision = OutboxDecision.ofDead(3, "terminal failure");

        when(decisionMaker.decide(eq(NOW), eq(0), eq(failure))).thenReturn(decision);

        ConsumerFlowSupport.FailureResult result = support.handleFailure(
                QUEUE,
                EVENT_TYPE,
                AGGREGATE_TYPE,
                PAYLOAD,
                102L,
                message(),
                NOW,
                failure
        );

        assertThat(result.dead()).isTrue();
        verify(store).markDead(102L, NOW, "terminal failure");
        verify(retryRecorder, never()).recordRetryRequest(anyLong(), any(), any(), any(), any(), any());
    }

    private ConsumerFlowSupport support(
            ProcessedMessageStore store,
            ConsumerDecisionMaker decisionMaker,
            ConsumerRetryRequestRecorder retryRecorder
    ) {
        return new ConsumerFlowSupport(store, decisionMaker, retryRecorder, new SimpleMeterRegistry(), CLOCK);
    }

    private Message message() {
        MessageProperties properties = new MessageProperties();
        properties.setHeader(ConsumerListenerSupport.RETRY_HEADER, 0);
        return new Message(PAYLOAD.getBytes(StandardCharsets.UTF_8), properties);
    }
}
