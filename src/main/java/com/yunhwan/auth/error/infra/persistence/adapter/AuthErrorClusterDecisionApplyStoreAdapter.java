package com.yunhwan.auth.error.infra.persistence.adapter;

import com.yunhwan.auth.error.domain.autherror.cluster.AuthErrorClusterDecisionApply;
import com.yunhwan.auth.error.infra.persistence.jpa.AuthErrorClusterDecisionApplyJpaRepository;
import com.yunhwan.auth.error.usecase.autherror.port.AuthErrorClusterDecisionApplyStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
@Transactional
public class AuthErrorClusterDecisionApplyStoreAdapter implements AuthErrorClusterDecisionApplyStore {

    private final AuthErrorClusterDecisionApplyJpaRepository repo;

    @Override
    public boolean exists(Long decisionId, Long authErrorId) {
        return repo.existsByDecisionIdAndAuthErrorId(decisionId, authErrorId);
    }

    @Override
    public AuthErrorClusterDecisionApply save(AuthErrorClusterDecisionApply apply) {
        return repo.save(apply);
    }
}
