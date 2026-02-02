package com.yunhwan.auth.error.infra.persistence.jpa;

import com.yunhwan.auth.error.domain.autherror.cluster.AuthErrorCluster;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AuthErrorClusterJpaRepository extends JpaRepository<AuthErrorCluster, Long> {
    Optional<AuthErrorCluster> findByClusterKey(String clusterKey);
}
