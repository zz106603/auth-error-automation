package com.yunhwan.auth.error.infra.persistence.adapter;

import com.yunhwan.auth.error.domain.autherror.cluster.AuthErrorClusterDecision;
import com.yunhwan.auth.error.infra.persistence.jpa.AuthErrorClusterDecisionJpaRepository;
import com.yunhwan.auth.error.usecase.autherror.port.AuthErrorClusterDecisionStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Repository
@RequiredArgsConstructor
@Transactional
public class AuthErrorClusterDecisionStoreAdapter implements AuthErrorClusterDecisionStore {

    private final AuthErrorClusterDecisionJpaRepository repo;

    @Override
    public Optional<AuthErrorClusterDecision> findByIdempotencyKey(String idempotencyKey) {
        return repo.findByIdempotencyKey(idempotencyKey);
    }

    @Override
    public AuthErrorClusterDecision save(AuthErrorClusterDecision decision) {
        return repo.save(decision);
    }
}
