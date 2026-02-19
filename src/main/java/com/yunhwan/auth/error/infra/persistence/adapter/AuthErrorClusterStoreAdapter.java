package com.yunhwan.auth.error.infra.persistence.adapter;

import com.yunhwan.auth.error.domain.autherror.AuthError;
import com.yunhwan.auth.error.domain.autherror.cluster.AuthErrorCluster;
import com.yunhwan.auth.error.infra.persistence.jpa.AuthErrorClusterJpaRepository;
import com.yunhwan.auth.error.infra.persistence.jpa.AuthErrorJpaRepository;
import com.yunhwan.auth.error.usecase.autherror.port.AuthErrorClusterStore;
import com.yunhwan.auth.error.usecase.autherror.port.AuthErrorStore;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
@Transactional
public class AuthErrorClusterStoreAdapter implements AuthErrorClusterStore {

    private final AuthErrorClusterJpaRepository repo;

    @Override
    public Optional<AuthErrorCluster> findByClusterKey(String clusterKey) {
        return repo.findByClusterKey(clusterKey);
    }

    @Override
    public AuthErrorCluster save(AuthErrorCluster cluster) {
        return repo.save(cluster);
    }

    @Override
    public Page<AuthErrorCluster> findAll(Pageable pageable) {
        return repo.findAll(pageable);
    }

    @Override
    public Optional<AuthErrorCluster> findById(Long id) {
        return repo.findById(id);
    }

    @Override
    @Transactional
    public Long getOrCreateIdByClusterKey(String clusterKey, OffsetDateTime now) {
        return repo.upsertAndGetId(clusterKey, now);
    }

    @Override
    @Transactional
    public boolean insertItemAndIncrementIfInserted(Long clusterId, Long authErrorId, OffsetDateTime now) {
        // 삽입 성공시에만 count 증가(업데이트 1)
        int updated = repo.insertItemAndIncrementIfInserted(clusterId, authErrorId, now);
        return updated == 1;
    }

    @Override
    @Transactional
    public void touch(Long clusterId, OffsetDateTime now) {
        repo.touch(clusterId, now);
    }

}
