package com.yunhwan.auth.error.common.exception;

public class AuthErrorNotFoundException extends RuntimeException {
    public AuthErrorNotFoundException(Long authErrorId) {
        super("AuthError not found. authErrorId=" + authErrorId);
    }
}