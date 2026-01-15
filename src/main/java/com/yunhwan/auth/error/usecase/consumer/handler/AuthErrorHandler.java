package com.yunhwan.auth.error.usecase.consumer.handler;

import java.util.Map;

public interface AuthErrorHandler {
    void handle(String payloadJson, Map<String, Object> headers);
}
