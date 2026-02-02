package com.yunhwan.auth.error.usecase.autherror.port;

import com.yunhwan.auth.error.domain.autherror.cluster.AuthErrorClusterDecision;

import java.util.Optional;

public interface AuthErrorClusterDecisionStore {
    Optional<AuthErrorClusterDecision> findByIdempotencyKey(String idempotencyKey);
    AuthErrorClusterDecision save(AuthErrorClusterDecision decision);
}
