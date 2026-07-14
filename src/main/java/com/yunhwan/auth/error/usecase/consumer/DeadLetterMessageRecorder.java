package com.yunhwan.auth.error.usecase.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yunhwan.auth.error.domain.consumer.DeadLetterReasonCode;
import com.yunhwan.auth.error.domain.consumer.DeadLetterMessage;
import com.yunhwan.auth.error.domain.consumer.ReplayStatus;
import com.yunhwan.auth.error.usecase.consumer.dto.DeadLetterMessageRecordCommand;
import com.yunhwan.auth.error.usecase.consumer.dto.DeadLetterSourceSnapshot;
import com.yunhwan.auth.error.usecase.consumer.port.DeadLetterMessageStore;
import com.yunhwan.auth.error.usecase.consumer.port.DeadLetterSourceLookup;
import com.yunhwan.auth.error.usecase.consumer.support.ConsumerHeaderUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.core.Message;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class DeadLetterMessageRecorder {

    private final DeadLetterMessageStore store;
    private final DeadLetterSourceLookup sourceLookup;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Transactional
    public DeadLetterMessage record(String dlqQueue, String payload, Message message) {
        Map<String, Object> headers = message.getMessageProperties().getHeaders();
        Long outboxId = asLong(headers.get("outboxId"));
        String eventType = asString(headers.get("eventType"));
        String aggregateType = asString(headers.get("aggregateType"));
        Integer retryCount = retryCount(headers);
        String xDeathJson = xDeathJson(headers);
        String brokerDeathReason = brokerDeathReason(headers);
        String payloadHash = sha256(payload);
        DeadLetterSourceSnapshot snapshot = sourceLookup.findSnapshot(outboxId);
        DeadLetterReasonCode reasonCode = classify(
                outboxId,
                eventType,
                aggregateType,
                payload,
                brokerDeathReason,
                snapshot
        );

        OffsetDateTime now = OffsetDateTime.now(clock);
        DeadLetterMessageRecordCommand command = new DeadLetterMessageRecordCommand(
                dedupeKey(dlqQueue, outboxId, eventType, payloadHash, brokerDeathReason),
                dlqQueue,
                sourceQueue(headers),
                firstDeathExchange(headers),
                firstDeathRoutingKey(headers),
                message.getMessageProperties().getReceivedExchange(),
                message.getMessageProperties().getReceivedRoutingKey(),
                outboxId,
                eventType,
                aggregateType,
                payload,
                payloadHash,
                payload.getBytes(StandardCharsets.UTF_8).length,
                reasonCode,
                brokerDeathReason,
                xDeathJson,
                retryCount,
                snapshot.processedMessageStatus(),
                snapshot.outboxStatus(),
                replayStatus(reasonCode),
                now
        );
        return store.upsert(command);
    }

    private DeadLetterReasonCode classify(Long outboxId,
                                          String eventType,
                                          String aggregateType,
                                          String payload,
                                          String brokerDeathReason,
                                          DeadLetterSourceSnapshot snapshot) {
        if (outboxId == null) {
            return DeadLetterReasonCode.CONTRACT_MISSING_OUTBOX_ID;
        }
        if (isBlank(eventType)) {
            return DeadLetterReasonCode.CONTRACT_MISSING_EVENT_TYPE;
        }
        if (isBlank(aggregateType)) {
            return DeadLetterReasonCode.CONTRACT_MISSING_AGGREGATE_TYPE;
        }

        DeadLetterReasonCode payloadReason = classifyPayload(payload);
        if (payloadReason != null) {
            return payloadReason;
        }

        String lastError = snapshot.processedMessageLastError();
        if (lastError != null) {
            String normalized = lastError.toLowerCase();
            if (normalized.contains("auth_error not found") || normalized.contains("autherror not found")) {
                return DeadLetterReasonCode.DOMAIN_AUTH_ERROR_NOT_FOUND;
            }
            if (normalized.contains("nonretryable") || normalized.contains("non-retryable")) {
                return DeadLetterReasonCode.HANDLER_NON_RETRYABLE;
            }
        }

        if ("DEAD".equals(snapshot.processedMessageStatus())) {
            return DeadLetterReasonCode.RETRY_EXHAUSTED;
        }
        if ("expired".equalsIgnoreCase(brokerDeathReason)) {
            return DeadLetterReasonCode.BROKER_EXPIRED;
        }
        if ("maxlen".equalsIgnoreCase(brokerDeathReason)) {
            return DeadLetterReasonCode.BROKER_MAXLEN;
        }
        if ("rejected".equalsIgnoreCase(brokerDeathReason)) {
            return DeadLetterReasonCode.BROKER_REJECTED;
        }
        return DeadLetterReasonCode.UNKNOWN;
    }

    private DeadLetterReasonCode classifyPayload(String payload) {
        JsonNode node;
        try {
            node = objectMapper.readTree(payload);
        } catch (JsonProcessingException e) {
            return DeadLetterReasonCode.PAYLOAD_INVALID_JSON;
        }
        JsonNode authErrorId = node.get("authErrorId");
        if (authErrorId == null || authErrorId.isNull()) {
            return DeadLetterReasonCode.PAYLOAD_MISSING_AUTH_ERROR_ID;
        }
        return null;
    }

    private ReplayStatus replayStatus(DeadLetterReasonCode reasonCode) {
        return switch (reasonCode) {
            case RETRY_EXHAUSTED -> ReplayStatus.REPLAYABLE;
            case CONSUMER_PROCESSING_FAILED, BROKER_REJECTED, BROKER_EXPIRED, BROKER_MAXLEN, UNKNOWN ->
                    ReplayStatus.BLOCKED;
            default -> ReplayStatus.NOT_REPLAYABLE;
        };
    }

    private String dedupeKey(String dlqQueue, Long outboxId, String eventType, String payloadHash, String brokerDeathReason) {
        return sha256(nullToEmpty(dlqQueue)
                + "\n" + nullToEmpty(outboxId)
                + "\n" + nullToEmpty(eventType)
                + "\n" + nullToEmpty(payloadHash)
                + "\n" + nullToEmpty(brokerDeathReason));
    }

    private String xDeathJson(Map<String, Object> headers) {
        Object xDeath = headers.get("x-death");
        if (xDeath == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(xDeath);
        } catch (JsonProcessingException e) {
            return "[{\"serialization\":\"failed\"}]";
        }
    }

    @SuppressWarnings("unchecked")
    private String brokerDeathReason(Map<String, Object> headers) {
        Object xDeath = headers.get("x-death");
        if (!(xDeath instanceof List<?> deaths) || deaths.isEmpty()) {
            return null;
        }
        Object first = deaths.getFirst();
        if (!(first instanceof Map<?, ?> death)) {
            return null;
        }
        return asString(death.get("reason"));
    }

    @SuppressWarnings("unchecked")
    private String sourceQueue(Map<String, Object> headers) {
        Object xDeath = headers.get("x-death");
        if (!(xDeath instanceof List<?> deaths) || deaths.isEmpty()) {
            return asString(headers.get("x-first-death-queue"));
        }
        Object first = deaths.getFirst();
        if (!(first instanceof Map<?, ?> death)) {
            return asString(headers.get("x-first-death-queue"));
        }
        return asString(death.get("queue"));
    }

    private String firstDeathExchange(Map<String, Object> headers) {
        return asString(headers.get("x-first-death-exchange"));
    }

    private String firstDeathRoutingKey(Map<String, Object> headers) {
        return asString(headers.get("x-first-death-routing-key"));
    }

    private Integer retryCount(Map<String, Object> headers) {
        try {
            return ConsumerHeaderUtils.getRetryCount(headers);
        } catch (Exception e) {
            return null;
        }
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(nullToEmpty(value).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private Long asLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String asString(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String nullToEmpty(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
