package com.yunhwan.auth.error.app.autherror;

import java.util.Locale;

final class AuthFailureContextNormalizer {

    private static final int MAX_PROVIDER_CHARS = 100;
    private static final int MAX_CLIENT_TYPE_CHARS = 50;
    private static final int MAX_ENDPOINT_CHARS = 300;
    private static final int MAX_USER_AGENT_FAMILY_CHARS = 100;
    private AuthFailureContextNormalizer() {
    }

    static String normalizeProvider(String value) {
        return normalizeToken(value, MAX_PROVIDER_CHARS);
    }

    static String normalizeClientType(String value) {
        return normalizeToken(value, MAX_CLIENT_TYPE_CHARS);
    }

    static String normalizeEndpoint(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            return null;
        }
        if (normalized.indexOf('?') >= 0) {
            normalized = normalized.substring(0, normalized.indexOf('?'));
        }
        normalized = normalized.replaceAll("/{2,}", "/");
        return truncate(normalized, MAX_ENDPOINT_CHARS);
    }

    static String normalizeHash(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.matches("^[0-9a-f]{64}$")) {
            return normalized;
        }
        return null;
    }

    static String normalizeUserAgentFamily(String value) {
        return normalizeToken(value, MAX_USER_AGENT_FAMILY_CHARS);
    }

    private static String normalizeToken(String value, int maxChars) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT)
                .replace('-', '_')
                .replaceAll("[^A-Z0-9_./]", "_")
                .replaceAll("_+", "_");
        if (normalized.isEmpty()) {
            return null;
        }
        return truncate(normalized, maxChars);
    }

    private static String truncate(String value, int maxChars) {
        if (value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, maxChars);
    }
}
