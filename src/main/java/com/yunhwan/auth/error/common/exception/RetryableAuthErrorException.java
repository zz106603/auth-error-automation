package com.yunhwan.auth.error.common.exception;

/**
 * 재시도 가치가 있는(일시 장애) 케이스.
 * 예: 외부 API 타임아웃, 네트워크 오류, 일시적 DB 장애 등
 */
public class RetryableAuthErrorException extends RuntimeException {

    public RetryableAuthErrorException(String message) {
        super(message);
    }

    public RetryableAuthErrorException(String message, Throwable cause) {
        super(message, cause);
    }
}
