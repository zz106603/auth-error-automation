package com.yunhwan.auth.error.usecase.consumer.port;

public interface DlqHandler {
    void onDlq(Long outboxId, String payload);
}
