package com.yunhwan.auth.error.common.exception;

public class NonRetryablePublishException extends RuntimeException {
    public NonRetryablePublishException(String msg) {
        super(msg);
    }
}
