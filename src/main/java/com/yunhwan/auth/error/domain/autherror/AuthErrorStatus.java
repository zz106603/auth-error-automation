package com.yunhwan.auth.error.domain.autherror;

public enum AuthErrorStatus {
    NEW(false),
    RETRY(false),
    ANALYSIS_REQUESTED(false),
    ANALYSIS_COMPLETED(false),
    PROCESSED(true),
    FAILED(true),
    RESOLVED(true),
    IGNORED(true);

    private final boolean terminal;

    AuthErrorStatus(boolean terminal) {
        this.terminal = terminal;
    }

    public boolean isTerminal() {
        return terminal;
    }
}
