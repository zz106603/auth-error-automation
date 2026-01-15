package com.yunhwan.auth.error.usecase.consumer.observer;

public interface DlqObserver {
    void onDlq(Long outboxId, String payload);
}
