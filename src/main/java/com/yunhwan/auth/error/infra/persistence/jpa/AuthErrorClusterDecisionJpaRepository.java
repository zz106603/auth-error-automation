package com.yunhwan.auth.error.infra.persistence.jpa;

import com.yunhwan.auth.error.domain.autherror.cluster.AuthErrorClusterDecision;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AuthErrorClusterDecisionJpaRepository extends JpaRepository<AuthErrorClusterDecision, Long> {
    Optional<AuthErrorClusterDecision> findByIdempotencyKey(String idempotencyKey);
}
