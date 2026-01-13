package com.yunhwan.auth.error.consumer.util;

import java.util.Map;

public final class HeaderUtils {
    public static final String RETRY_HEADER = "x-retry-count";

    private HeaderUtils() {}

    public static int getRetryCount(Map<String, Object> headers) {
        if (headers == null) {
            return 0;
        }
        Object v = headers.get(RETRY_HEADER);
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v instanceof String s) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException ignore) {
                // It's fine to ignore and return 0
            }
        }
        return 0;
    }
}
