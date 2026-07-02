package com.yunhwan.auth.error.infra.messaging.consumer.listener;

import com.rabbitmq.client.Channel;
import com.yunhwan.auth.error.domain.consumer.ProcessedStatus;
import com.yunhwan.auth.error.domain.outbox.decision.OutboxDecision;
import com.yunhwan.auth.error.infra.messaging.consumer.parser.JacksonAuthErrorAnalysisRequestedPayloadParser;
import com.yunhwan.auth.error.usecase.consumer.handler.AuthErrorHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Analysis consumer 공통 flow 회귀 테스트")
class AuthErrorAnalysisRequestedConsumerCommonFlowTest {

    private static final String QUEUE_EVENT = "auth.error.analysis.requested.v1";
    private static final String AGGREGATE_TYPE = "AUTH_ERROR";
    private static final String PAYLOAD = "{\"authErrorId\":1}";
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-01-01T00:00:00Z");

    @Test
    @DisplayName("success: handler 이후 markDone, 그 다음 ACK한다")
    void success_marks_done_then_acks() throws Exception {
        AuthErrorHandler handler = mock(AuthErrorHandler.class);
        JacksonAuthErrorAnalysisRequestedPayloadParser parser = mock(JacksonAuthErrorAnalysisRequestedPayloadParser.class);
        ConsumerFlowSupport flowSupport = mock(ConsumerFlowSupport.class);
        Channel channel = mock(Channel.class);
        Message message = message(10L);

        when(flowSupport.validateHeaders(100L, QUEUE_EVENT, AGGREGATE_TYPE))
                .thenReturn(ConsumerFlowSupport.HeaderValidationResult.ok());
        when(flowSupport.now()).thenReturn(NOW, NOW.plusSeconds(1));
        when(flowSupport.claim(100L, NOW)).thenReturn(new ConsumerFlowSupport.ClaimResult(true, null));
        when(flowSupport.retryCount(message)).thenReturn(0);

        consumer(handler, parser, flowSupport)
                .onMessage(PAYLOAD, message, channel, 100L, QUEUE_EVENT, AGGREGATE_TYPE);

        var ordered = inOrder(handler, flowSupport, channel);
        ordered.verify(handler).handle(eq(PAYLOAD), any());
        ordered.verify(flowSupport).markDone(any(), eq(QUEUE_EVENT), eq(100L), eq(NOW.plusSeconds(1)));
        ordered.verify(channel).basicAck(10L, false);
        verify(channel, never()).basicReject(anyLong(), eq(false));
    }

    @Test
    @DisplayName("retry: 실패 상태 기록 이후 원본 메시지를 ACK한다")
    void retry_records_retry_then_acks() throws Exception {
        AuthErrorHandler handler = mock(AuthErrorHandler.class);
        JacksonAuthErrorAnalysisRequestedPayloadParser parser = mock(JacksonAuthErrorAnalysisRequestedPayloadParser.class);
        ConsumerFlowSupport flowSupport = mock(ConsumerFlowSupport.class);
        Channel channel = mock(Channel.class);
        Message message = message(11L);
        RuntimeException failure = new RuntimeException("temporary failure");
        OutboxDecision decision = OutboxDecision.ofRetry(1, NOW.plusSeconds(5), "temporary failure");

        when(flowSupport.validateHeaders(101L, QUEUE_EVENT, AGGREGATE_TYPE))
                .thenReturn(ConsumerFlowSupport.HeaderValidationResult.ok());
        when(flowSupport.now()).thenReturn(NOW);
        when(flowSupport.claim(101L, NOW)).thenReturn(new ConsumerFlowSupport.ClaimResult(true, null));
        when(flowSupport.retryCount(message)).thenReturn(0);
        doThrow(failure).when(handler).handle(eq(PAYLOAD), any());
        when(flowSupport.handleFailure(any(), eq(QUEUE_EVENT), eq(AGGREGATE_TYPE), eq(PAYLOAD), eq(101L), eq(message), eq(NOW), eq(failure)))
                .thenReturn(ConsumerFlowSupport.FailureResult.retry(decision));

        consumer(handler, parser, flowSupport)
                .onMessage(PAYLOAD, message, channel, 101L, QUEUE_EVENT, AGGREGATE_TYPE);

        var ordered = inOrder(flowSupport, channel);
        ordered.verify(flowSupport).handleFailure(any(), eq(QUEUE_EVENT), eq(AGGREGATE_TYPE), eq(PAYLOAD), eq(101L), eq(message), eq(NOW), eq(failure));
        ordered.verify(channel).basicAck(11L, false);
        verify(channel, never()).basicReject(anyLong(), eq(false));
    }

    @Test
    @DisplayName("dead/DLQ: DEAD 상태 기록 이후 reject(false)로 DLQ 격리한다")
    void dead_records_dead_then_rejects() throws Exception {
        AuthErrorHandler handler = mock(AuthErrorHandler.class);
        JacksonAuthErrorAnalysisRequestedPayloadParser parser = mock(JacksonAuthErrorAnalysisRequestedPayloadParser.class);
        ConsumerFlowSupport flowSupport = mock(ConsumerFlowSupport.class);
        Channel channel = mock(Channel.class);
        Message message = message(12L);
        RuntimeException failure = new RuntimeException("terminal failure");
        OutboxDecision decision = OutboxDecision.ofDead(3, "terminal failure");

        when(flowSupport.validateHeaders(102L, QUEUE_EVENT, AGGREGATE_TYPE))
                .thenReturn(ConsumerFlowSupport.HeaderValidationResult.ok());
        when(flowSupport.now()).thenReturn(NOW);
        when(flowSupport.claim(102L, NOW)).thenReturn(new ConsumerFlowSupport.ClaimResult(true, null));
        when(flowSupport.retryCount(message)).thenReturn(2);
        doThrow(failure).when(handler).handle(eq(PAYLOAD), any());
        when(flowSupport.handleFailure(any(), eq(QUEUE_EVENT), eq(AGGREGATE_TYPE), eq(PAYLOAD), eq(102L), eq(message), eq(NOW), eq(failure)))
                .thenReturn(ConsumerFlowSupport.FailureResult.dead(decision));

        consumer(handler, parser, flowSupport)
                .onMessage(PAYLOAD, message, channel, 102L, QUEUE_EVENT, AGGREGATE_TYPE);

        var ordered = inOrder(flowSupport, channel);
        ordered.verify(flowSupport).handleFailure(any(), eq(QUEUE_EVENT), eq(AGGREGATE_TYPE), eq(PAYLOAD), eq(102L), eq(message), eq(NOW), eq(failure));
        ordered.verify(channel).basicReject(12L, false);
        verify(channel, never()).basicAck(anyLong(), eq(false));
    }

    @Test
    @DisplayName("duplicate delivery: claim 실패 시 handler 없이 ACK(drop)한다")
    void duplicate_delivery_ack_drops_without_handler() throws Exception {
        AuthErrorHandler handler = mock(AuthErrorHandler.class);
        JacksonAuthErrorAnalysisRequestedPayloadParser parser = mock(JacksonAuthErrorAnalysisRequestedPayloadParser.class);
        ConsumerFlowSupport flowSupport = mock(ConsumerFlowSupport.class);
        Channel channel = mock(Channel.class);
        Message message = message(13L);

        when(flowSupport.validateHeaders(103L, QUEUE_EVENT, AGGREGATE_TYPE))
                .thenReturn(ConsumerFlowSupport.HeaderValidationResult.ok());
        when(flowSupport.now()).thenReturn(NOW);
        when(flowSupport.claim(103L, NOW)).thenReturn(new ConsumerFlowSupport.ClaimResult(false, ProcessedStatus.DONE));

        consumer(handler, parser, flowSupport)
                .onMessage(PAYLOAD, message, channel, 103L, QUEUE_EVENT, AGGREGATE_TYPE);

        verify(handler, never()).handle(any(), any());
        verify(channel).basicAck(13L, false);
        verify(channel, never()).basicReject(anyLong(), eq(false));
    }

    private AuthErrorAnalysisRequestedConsumer consumer(
            AuthErrorHandler handler,
            JacksonAuthErrorAnalysisRequestedPayloadParser parser,
            ConsumerFlowSupport flowSupport
    ) {
        return new AuthErrorAnalysisRequestedConsumer(handler, parser, flowSupport);
    }

    private Message message(long deliveryTag) {
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(deliveryTag);
        properties.setHeader(ConsumerListenerSupport.RETRY_HEADER, 0);
        return new Message(PAYLOAD.getBytes(StandardCharsets.UTF_8), properties);
    }
}
