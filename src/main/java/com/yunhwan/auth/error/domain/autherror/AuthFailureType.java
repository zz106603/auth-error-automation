package com.yunhwan.auth.error.domain.autherror;

import java.util.Locale;

public enum AuthFailureType {
    INVALID_CREDENTIALS(AuthFailureSeverity.LOW, false, false),
    TOKEN_EXPIRED(AuthFailureSeverity.LOW, false, false),
    TOKEN_INVALID_SIGNATURE(AuthFailureSeverity.HIGH, false, true),
    ACCOUNT_LOCKED(AuthFailureSeverity.MEDIUM, false, false),
    MFA_FAILED(AuthFailureSeverity.MEDIUM, false, false),
    RATE_LIMITED(AuthFailureSeverity.MEDIUM, false, true),
    AUTH_PROVIDER_TIMEOUT(AuthFailureSeverity.HIGH, true, false),
    AUTH_PROVIDER_5XX(AuthFailureSeverity.HIGH, true, false),
    UNKNOWN_AUTH_ERROR(AuthFailureSeverity.MEDIUM, false, false);

    private final AuthFailureSeverity severity;
    private final boolean retryable;
    private final boolean securitySignal;

    AuthFailureType(AuthFailureSeverity severity, boolean retryable, boolean securitySignal) {
        this.severity = severity;
        this.retryable = retryable;
        this.securitySignal = securitySignal;
    }

    public AuthFailureSeverity severity() {
        return severity;
    }

    public boolean retryable() {
        return retryable;
    }

    public boolean securitySignal() {
        return securitySignal;
    }

    public static AuthFailureType from(String value) {
        if (value == null || value.isBlank()) {
            return UNKNOWN_AUTH_ERROR;
        }
        try {
            return AuthFailureType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return UNKNOWN_AUTH_ERROR;
        }
    }
}
