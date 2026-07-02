package com.yunhwan.auth.error.infra.messaging.consumer.listener;

import com.rabbitmq.client.Channel;
import com.yunhwan.auth.error.infra.metrics.MetricsConfig;
import com.yunhwan.auth.error.infra.messaging.consumer.parser.JacksonAuthErrorAnalysisRequestedPayloadParser;
import com.yunhwan.auth.error.infra.messaging.rabbit.RabbitTopologyConfig;
import com.yunhwan.auth.error.usecase.consumer.handler.AuthErrorHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

import static com.yunhwan.auth.error.infra.messaging.consumer.listener.ConsumerListenerSupport.buildHeaders;
import static com.yunhwan.auth.error.infra.messaging.consumer.listener.ConsumerListenerSupport.payloadSizeBytes;

@Slf4j
@Component
public class AuthErrorAnalysisRequestedConsumer {

    private final AuthErrorHandler handler;
    private final JacksonAuthErrorAnalysisRequestedPayloadParser payloadParser;
    private final ConsumerFlowSupport flowSupport;

    public AuthErrorAnalysisRequestedConsumer(
            @Qualifier("authErrorAnalysisRequestedHandler") AuthErrorHandler handler,
            JacksonAuthErrorAnalysisRequestedPayloadParser payloadParser,
            ConsumerFlowSupport flowSupport
    ) {
        this.handler = handler;
        this.payloadParser = payloadParser;
        this.flowSupport = flowSupport;
    }

    @RabbitListener(queues = RabbitTopologyConfig.Q_ANALYSIS)
    public void onMessage(
            String payload,
            Message message,
            Channel channel,
            @Header(name = "outboxId", required = false) Long outboxId,
            @Header(name = "eventType", required = false) String eventType,
            @Header(name = "aggregateType", required = false) String aggregateType
    ) throws Exception {

        long tag = message.getMessageProperties().getDeliveryTag();

        ConsumerFlowSupport.HeaderValidationResult headerValidation =
                flowSupport.validateHeaders(outboxId, eventType, aggregateType);
        if (!headerValidation.valid() && outboxId == null) {
            log.warn("[AnalysisConsumer] missing outboxId -> reject(DLQ). payloadSizeBytes={}",
                    payloadSizeBytes(payload));
            flowSupport.recordReject(RabbitTopologyConfig.Q_ANALYSIS, eventType, headerValidation.rejectReason());
            channel.basicReject(tag, false);
            return;
        }

        if (!headerValidation.valid()) {
            log.warn("[AnalysisConsumer] missing headers -> reject(DLQ). outboxId={}, eventType={}, aggregateType={}",
                    outboxId, eventType, aggregateType);
            flowSupport.recordReject(RabbitTopologyConfig.Q_ANALYSIS, eventType, headerValidation.rejectReason());
            channel.basicReject(tag, false);
            return;
        }

        try {
            payloadParser.parse(payload, outboxId);
        } catch (Exception e) {
            log.warn("[AnalysisConsumer] invalid payload -> reject(DLQ). outboxId={}, err={}", outboxId, e.getMessage());
            flowSupport.recordReject(RabbitTopologyConfig.Q_ANALYSIS, eventType, MetricsConfig.REASON_INVALID_PAYLOAD);
            channel.basicReject(tag, false);
            return;
        }

        OffsetDateTime now = flowSupport.now();

        ConsumerFlowSupport.ClaimResult claimResult = flowSupport.claim(outboxId, now);
        if (!claimResult.claimed()) {
            log.warn("[AnalysisConsumer] claim failed -> ack(drop). outboxId={}, status={}", outboxId, claimResult.status());
            channel.basicAck(tag, false);
            return;
        }

        try {
            int currentRetry = flowSupport.retryCount(message);

            handler.handle(payload, buildHeaders(outboxId, eventType, aggregateType, currentRetry));

            flowSupport.markDone(RabbitTopologyConfig.Q_ANALYSIS, eventType, outboxId, flowSupport.now());
            // E2E는 recorded 이벤트에서만 측정(중복 방지)
            channel.basicAck(tag, false);

        } catch (Exception e) {
            ConsumerFlowSupport.FailureResult failure = flowSupport.handleFailure(
                    RabbitTopologyConfig.Q_ANALYSIS,
                    eventType,
                    aggregateType,
                    payload,
                    outboxId,
                    message,
                    now,
                    e
            );

            if (failure.dead()) {
                log.error("[AnalysisConsumer] DEAD -> DLQ outboxId={}, err={}", outboxId, failure.decision().lastError(), e);
                channel.basicReject(tag, false);
                return;
            }

            log.warn("[AnalysisConsumer] RETRY -> outboxId={}, nextRetryCount={}, nextRetryAt={}, err={}",
                    outboxId, failure.decision().nextRetryCount(), failure.decision().nextRetryAt(), failure.decision().lastError());

            channel.basicAck(tag, false);
        }
    }
}
