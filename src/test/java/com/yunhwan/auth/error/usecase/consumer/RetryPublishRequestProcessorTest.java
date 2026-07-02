package com.yunhwan.auth.error.usecase.consumer;

import com.yunhwan.auth.error.common.exception.RetryablePublishException;
import com.yunhwan.auth.error.domain.consumer.RetryPublishRequest;
import com.yunhwan.auth.error.usecase.consumer.policy.RetryPolicy;
import com.yunhwan.auth.error.usecase.consumer.port.ProcessedMessageStore;
import com.yunhwan.auth.error.usecase.consumer.port.RetryPublishRequestPublisher;
import com.yunhwan.auth.error.usecase.consumer.port.RetryPublishRequestStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.lang.reflect.Constructor;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("RetryPublishRequestProcessor 정책 테스트")
class RetryPublishRequestProcessorTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    @Test
    @DisplayName("단일 retry publish 실패는 processed_message RETRY_WAIT를 DEAD로 전파하지 않는다")
    void retryable_publish_failure_does_not_mark_processed_dead() throws Exception {
        RetryPublishRequestPublisher publisher = mock(RetryPublishRequestPublisher.class);
        RetryPublishRequestStore store = mock(RetryPublishRequestStore.class);
        ProcessedMessageStore processedMessageStore = mock(ProcessedMessageStore.class);

        RetryPublishRequestProcessor processor = new RetryPublishRequestProcessor(
                publisher,
                store,
                processedMessageStore,
                retryPolicy(2),
                CLOCK
        );
        RetryPublishRequest request = request(10L, 2002L, 0);

        doThrow(new RetryablePublishException("confirm timeout", null))
                .when(publisher).publish(any());
        when(store.markForRetry(eq(10L), eq("owner-1"), eq(1), any(), eq("confirm timeout"), any()))
                .thenReturn(1);

        processor.process("owner-1", List.of(request));

        verify(store).markForRetry(eq(10L), eq("owner-1"), eq(1), any(), eq("confirm timeout"), any());
        verify(store, never()).markDead(anyLong(), anyString(), anyInt(), anyString(), any());
        verify(processedMessageStore, never()).markDeadFromRetryPublishRequest(anyLong(), any(), anyString());
    }

    @Test
    @DisplayName("RetryPublishRequest terminal DEAD만 원본 processed_message DEAD로 전파한다")
    void terminal_dead_marks_processed_dead() throws Exception {
        RetryPublishRequestPublisher publisher = mock(RetryPublishRequestPublisher.class);
        RetryPublishRequestStore store = mock(RetryPublishRequestStore.class);
        ProcessedMessageStore processedMessageStore = mock(ProcessedMessageStore.class);

        RetryPublishRequestProcessor processor = new RetryPublishRequestProcessor(
                publisher,
                store,
                processedMessageStore,
                retryPolicy(2),
                CLOCK
        );
        RetryPublishRequest request = request(11L, 3003L, 1);

        doThrow(new RetryablePublishException("confirm timeout", null))
                .when(publisher).publish(any());
        when(store.markDead(eq(11L), eq("owner-1"), eq(2), eq("confirm timeout"), any()))
                .thenReturn(1);

        processor.process("owner-1", List.of(request));

        verify(store).markDead(eq(11L), eq("owner-1"), eq(2), eq("confirm timeout"), any());
        verify(processedMessageStore).markDeadFromRetryPublishRequest(
                eq(3003L),
                eq(OffsetDateTime.now(CLOCK)),
                eq("RETRY_PUBLISH_REQUEST_DEAD: confirm timeout")
        );
    }

    private RetryPolicy retryPolicy(int maxRetries) {
        return new RetryPolicy() {
            @Override
            public int nextRetryCount(int currentRetryCount) {
                return currentRetryCount + 1;
            }

            @Override
            public boolean shouldDead(int nextRetryCount) {
                return nextRetryCount >= maxRetries;
            }

            @Override
            public OffsetDateTime nextRetryAt(OffsetDateTime now, int nextRetryCount) {
                return now.plusSeconds(1);
            }
        };
    }

    private RetryPublishRequest request(long id, long sourceOutboxId, int publishRetryCount) {
        RetryPublishRequest request = newRequest();
        ReflectionTestUtils.setField(request, "id", id);
        ReflectionTestUtils.setField(request, "sourceOutboxId", sourceOutboxId);
        ReflectionTestUtils.setField(request, "eventType", "auth.error.recorded.v1");
        ReflectionTestUtils.setField(request, "aggregateType", "AUTH_ERROR");
        ReflectionTestUtils.setField(request, "payload", "{\"authErrorId\":1}");
        ReflectionTestUtils.setField(request, "retryCount", 1);
        ReflectionTestUtils.setField(request, "nextRetryAt", OffsetDateTime.now(CLOCK).plusSeconds(1));
        ReflectionTestUtils.setField(request, "publishRetryCount", publishRetryCount);
        return request;
    }

    private RetryPublishRequest newRequest() {
        try {
            Constructor<RetryPublishRequest> constructor = RetryPublishRequest.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (Exception e) {
            throw new IllegalStateException("RetryPublishRequest test instance creation failed", e);
        }
    }
}
