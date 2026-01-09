package com.yunhwan.auth.error.outbox.support.impl;

import com.yunhwan.auth.error.domain.auth.AuthError;
import com.yunhwan.auth.error.outbox.support.api.IdempotencyKeyResolver;
import org.springframework.stereotype.Component;

@Component
public class AuthErrorIdempotencyKeyResolver implements IdempotencyKeyResolver<AuthError> {

    @Override
    public String resolve(AuthError saved) {
        if (saved.getRequestId() != null && !saved.getRequestId().isBlank()) {
            return saved.getRequestId();
        }
        if (saved.getDedupKey() != null && !saved.getDedupKey().isBlank()) {
            return saved.getDedupKey();
        }
        // 최후: authErrorId 기반 (동일 TX 내 유일)
        return "AUTH_ERROR:" + saved.getId();
    }
}
