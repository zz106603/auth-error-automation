package com.yunhwan.auth.error.consumer.handler;

import java.util.Map;

public interface AuthErrorHandler {
    void handle(String payloadJson, Map<String, Object> headers);
}
