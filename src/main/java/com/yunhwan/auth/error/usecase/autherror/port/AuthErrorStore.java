package com.yunhwan.auth.error.usecase.autherror.port;

import com.yunhwan.auth.error.domain.autherror.AuthError;

import java.util.Optional;

public interface AuthErrorStore {
    AuthError save(AuthError authError);
    Optional<AuthError> findById(Long id);
    Optional<AuthError> findByDedupKey(String dedupKey);
}
