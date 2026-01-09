package com.yunhwan.auth.error.common.exception;


/**
 * 재시도해도 의미 없는(영구 실패) 케이스.
 * 예: payload 형식 오류, 필수 필드 누락, 데이터 정합성 문제 등
 */
public class NonRetryableAuthErrorException extends RuntimeException {

    public NonRetryableAuthErrorException(String message) {
        super(message);
    }

    public NonRetryableAuthErrorException(String message, Throwable cause) {
        super(message, cause);
    }
}
