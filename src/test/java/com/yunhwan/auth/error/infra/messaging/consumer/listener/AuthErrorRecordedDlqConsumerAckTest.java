package com.yunhwan.auth.error.infra.messaging.consumer.listener;

import com.rabbitmq.client.Channel;
import com.yunhwan.auth.error.usecase.consumer.DeadLetterMessageRecorder;
import com.yunhwan.auth.error.usecase.consumer.port.DlqHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@DisplayName("[02] DLQ consumer ACK 경계 테스트")
class AuthErrorRecordedDlqConsumerAckTest {

    @Test
    @DisplayName("ledger 저장 실패 시 ACK하지 않는다")
    void ledger_저장_실패_시_ACK하지_않는다() throws Exception {
        DeadLetterMessageRecorder recorder = mock(DeadLetterMessageRecorder.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<DlqHandler> provider = mock(ObjectProvider.class);
        Channel channel = mock(Channel.class);
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(42L);
        properties.setHeader("outboxId", 100L);
        Message message = new Message("{}".getBytes(StandardCharsets.UTF_8), properties);

        when(recorder.record(eq("auth.error.recorded.q.dlq"), any(String.class), eq(message)))
                .thenThrow(new IllegalStateException("db unavailable"));

        AuthErrorRecordedDlqConsumer consumer = new AuthErrorRecordedDlqConsumer(
                provider,
                new SimpleMeterRegistry(),
                recorder
        );
        consumer.initMetrics();

        assertThatThrownBy(() -> consumer.onDlq("{}", message, channel, 100L))
                .isInstanceOf(IllegalStateException.class);

        verify(channel, never()).basicAck(anyLong(), anyBoolean());
        verifyNoInteractions(provider);
    }
}
