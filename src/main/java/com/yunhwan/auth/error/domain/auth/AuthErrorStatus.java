package com.yunhwan.auth.error.domain.auth;

public enum AuthErrorStatus {
    NEW,
    PROCESSING,
    RETRY,
    PROCESSED,
    FAILED,
    RESOLVED,
    IGNORED
}
