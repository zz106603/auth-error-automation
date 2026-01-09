package com.yunhwan.auth.error.outbox.support.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yunhwan.auth.error.outbox.support.api.OutboxPayloadSerializer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class JacksonOutboxPayloadSerializer implements OutboxPayloadSerializer {

    private final ObjectMapper objectMapper;

    @Override
    public String serialize(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize outbox payload", e);
        }
    }
}
