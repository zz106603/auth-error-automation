package com.yunhwan.auth.error.usecase.autherror.port;

import com.yunhwan.auth.error.domain.autherror.cluster.AuthErrorCluster;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Optional;

public interface AuthErrorClusterStore {
    Optional<AuthErrorCluster> findByClusterKey(String clusterKey);

    AuthErrorCluster save(AuthErrorCluster cluster);

    Page<AuthErrorCluster> findAll(Pageable pageable);

    Optional<AuthErrorCluster> findById(Long id);
}
