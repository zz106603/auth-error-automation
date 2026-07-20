package com.yunhwan.auth.error.mcp;

import java.util.Map;

record DiagnosticQuery(
        int hoursBack,
        int limit,
        String provider,
        String errorType,
        String requestId,
        String traceId,
        Long outboxId
) {

    static DiagnosticQuery from(Map<String, Object> args) {
        return new DiagnosticQuery(
                intArg(args, "hoursBack", 1, 1, 168),
                intArg(args, "limit", 10, 1, 50),
                textArg(args, "provider"),
                textArg(args, "errorType"),
                textArg(args, "requestId"),
                textArg(args, "traceId"),
                longArg(args, "outboxId")
        );
    }

    private static int intArg(Map<String, Object> args, String name, int defaultValue, int min, int max) {
        if (args == null || args.get(name) == null) {
            return defaultValue;
        }
        Object raw = args.get(name);
        int value = raw instanceof Number number ? number.intValue() : defaultValue;
        return Math.max(min, Math.min(max, value));
    }

    private static Long longArg(Map<String, Object> args, String name) {
        if (args == null || args.get(name) == null) {
            return null;
        }
        Object raw = args.get(name);
        long value = raw instanceof Number number ? number.longValue() : 0L;
        return value > 0 ? value : null;
    }

    private static String textArg(Map<String, Object> args, String name) {
        if (args == null || args.get(name) == null) {
            return null;
        }
        String value = args.get(name) instanceof String text ? text : null;
        return value == null || value.isBlank() ? null : value.trim();
    }
}
