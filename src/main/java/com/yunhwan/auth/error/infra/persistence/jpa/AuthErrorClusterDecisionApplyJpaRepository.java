package com.yunhwan.auth.error.infra.persistence.jpa;

import com.yunhwan.auth.error.domain.autherror.cluster.AuthErrorClusterDecisionApply;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuthErrorClusterDecisionApplyJpaRepository extends JpaRepository<AuthErrorClusterDecisionApply, Long> {
    boolean existsByDecisionIdAndAuthErrorId(Long decisionId, Long authErrorId);
}
