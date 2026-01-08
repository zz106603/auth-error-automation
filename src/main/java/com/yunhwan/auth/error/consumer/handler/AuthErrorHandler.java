package com.yunhwan.auth.error.consumer.handler;

import java.util.Map;

public interface AuthErrorHandler {
    void handle(String payload, Map<String, Object> headers);
}
