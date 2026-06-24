package com.yunhwan.auth.error.usecase.consumer.support;

import java.util.Map;

public final class ConsumerHeaderUtils {

    private static final String RETRY_HEADER = "x-retry-count";

    private ConsumerHeaderUtils() {
    }

    public static int getRetryCount(Map<String, Object> headers) {
        Object value = headers.get(RETRY_HEADER);
        if (value == null) {
            return 0;
        }
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
