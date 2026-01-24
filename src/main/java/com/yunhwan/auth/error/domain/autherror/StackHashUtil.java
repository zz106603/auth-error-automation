package com.yunhwan.auth.error.domain.autherror;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public final class StackHashUtil {
    private StackHashUtil() {}

    public static String compute(String exceptionClass, String stacktrace) {
        String basis = (safe(exceptionClass) + "\n" + topLines(stacktrace, 3)).trim();
        return sha256Hex(basis);
    }

    private static String topLines(String s, int n) {
        if (s == null || s.isBlank()) return "";
        String[] lines = s.split("\\R");
        StringBuilder sb = new StringBuilder();
        int count = Math.min(n, lines.length);
        for (int i = 0; i < count; i++) {
            sb.append(lines[i]).append('\n');
        }
        return sb.toString();
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] dig = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(dig.length * 2);
            for (byte b : dig) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("sha256 compute failed", e);
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
