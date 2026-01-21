package com.yunhwan.auth.error.common.exception;

public class RetryablePublishException extends RuntimeException {
    public RetryablePublishException(String msg, Throwable cause) {
        super(msg, cause);
    }
}
