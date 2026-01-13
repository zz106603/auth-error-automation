package com.yunhwan.auth.error.consumer.observer;

public interface DlqObserver {
    void onDlq(Long outboxId, String payload);
}
