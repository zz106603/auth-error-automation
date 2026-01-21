package com.yunhwan.auth.error.domain.autherror;

public enum AuthErrorStatus {
    NEW(false),
    RETRY(false),
    PROCESSED(true),
    FAILED(false),
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
