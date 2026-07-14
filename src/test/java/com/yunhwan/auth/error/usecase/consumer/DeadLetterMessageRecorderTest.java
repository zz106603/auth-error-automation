package com.yunhwan.auth.error.usecase.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yunhwan.auth.error.domain.consumer.ReplayStatus;
import com.yunhwan.auth.error.usecase.consumer.dto.DeadLetterMessageRecordCommand;
import com.yunhwan.auth.error.usecase.consumer.dto.DeadLetterSourceSnapshot;
import com.yunhwan.auth.error.usecase.consumer.port.DeadLetterMessageStore;
import com.yunhwan.auth.error.usecase.consumer.port.DeadLetterSourceLookup;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("[11] DLQ replay policy")
class DeadLetterMessageRecorderTest {

    @Test
    @DisplayName("RETRY_EXHAUSTED는 조건부 replay 후보로 기록한다")
    void retry_exhausted는_replayable로_기록한다() {
        DeadLetterMessageStore store = mock(DeadLetterMessageStore.class);
        DeadLetterSourceLookup sourceLookup = mock(DeadLetterSourceLookup.class);
        when(sourceLookup.findSnapshot(100L))
                .thenReturn(new DeadLetterSourceSnapshot("DEAD", null, 5, "PUBLISHED"));

        DeadLetterMessageRecorder recorder = new DeadLetterMessageRecorder(
                store,
                sourceLookup,
                new ObjectMapper(),
                fixedClock()
        );

        recorder.record("auth.error.recorded.q.dlq", "{\"authErrorId\":100}", message(100L));

        DeadLetterMessageRecordCommand command = capturedCommand(store);
        assertThat(command.reasonCode().name()).isEqualTo("RETRY_EXHAUSTED");
        assertThat(command.replayStatus()).isEqualTo(ReplayStatus.REPLAYABLE);
    }

    @Test
    @DisplayName("UNKNOWN은 원인 조사 전 replay 후보로 열어두지 않는다")
    void unknown은_blocked로_기록한다() {
        DeadLetterMessageStore store = mock(DeadLetterMessageStore.class);
        DeadLetterSourceLookup sourceLookup = mock(DeadLetterSourceLookup.class);
        when(sourceLookup.findSnapshot(101L))
                .thenReturn(new DeadLetterSourceSnapshot(null, null, null, null));

        DeadLetterMessageRecorder recorder = new DeadLetterMessageRecorder(
                store,
                sourceLookup,
                new ObjectMapper(),
                fixedClock()
        );

        recorder.record("auth.error.recorded.q.dlq", "{\"authErrorId\":101}", message(101L));

        DeadLetterMessageRecordCommand command = capturedCommand(store);
        assertThat(command.reasonCode().name()).isEqualTo("UNKNOWN");
        assertThat(command.replayStatus()).isEqualTo(ReplayStatus.BLOCKED);
    }

    private DeadLetterMessageRecordCommand capturedCommand(DeadLetterMessageStore store) {
        ArgumentCaptor<DeadLetterMessageRecordCommand> captor =
                ArgumentCaptor.forClass(DeadLetterMessageRecordCommand.class);
        verify(store).upsert(captor.capture());
        return captor.getValue();
    }

    private Message message(long outboxId) {
        MessageProperties properties = new MessageProperties();
        properties.setReceivedExchange("auth.error.recorded.dlx");
        properties.setReceivedRoutingKey("auth.error.recorded.v1.dlq");
        properties.setHeader("outboxId", outboxId);
        properties.setHeader("eventType", "auth.error.recorded.v1");
        properties.setHeader("aggregateType", "AUTH_ERROR");
        return new Message("{}".getBytes(StandardCharsets.UTF_8), properties);
    }

    private Clock fixedClock() {
        return Clock.fixed(Instant.parse("2026-07-14T00:00:00Z"), ZoneOffset.UTC);
    }
}
