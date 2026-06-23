package com.yunhwan.auth.error.usecase.consumer.port;

import com.yunhwan.auth.error.domain.consumer.RetryPublishRequest;

public interface RetryPublishRequestPublisher {
    void publish(RetryPublishRequest request) throws Exception;
}
