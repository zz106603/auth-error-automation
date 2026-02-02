package com.yunhwan.auth.error.infra.persistence.adapter;

import com.yunhwan.auth.error.domain.autherror.cluster.AuthErrorCluster;
import com.yunhwan.auth.error.domain.autherror.cluster.AuthErrorClusterItem;
import com.yunhwan.auth.error.infra.persistence.jpa.AuthErrorClusterItemJpaRepository;
import com.yunhwan.auth.error.infra.persistence.jpa.AuthErrorClusterJpaRepository;
import com.yunhwan.auth.error.usecase.autherror.port.AuthErrorClusterItemStore;
import com.yunhwan.auth.error.usecase.autherror.port.AuthErrorClusterStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
@Transactional
public class AuthErrorClusterItemStoreAdapter implements AuthErrorClusterItemStore {

    private final AuthErrorClusterItemJpaRepository repo;

    @Override
    public boolean existsById(AuthErrorClusterItem.PK pk) {
        return repo.existsById(pk);
    }

    @Override
    public AuthErrorClusterItem save(AuthErrorClusterItem item) {
        return repo.save(item);
    }

    @Override
    public List<Long> findAuthErrorIdsByClusterId(Long clusterId) {
        return repo.findAuthErrorIdsByClusterId(clusterId);
    }

}
