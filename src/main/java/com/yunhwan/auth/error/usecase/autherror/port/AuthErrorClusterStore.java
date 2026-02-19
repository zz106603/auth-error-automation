package com.yunhwan.auth.error.usecase.autherror.port;

import com.yunhwan.auth.error.domain.autherror.cluster.AuthErrorCluster;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.OffsetDateTime;
import java.util.Optional;

public interface AuthErrorClusterStore {
    Optional<AuthErrorCluster> findByClusterKey(String clusterKey);

    AuthErrorCluster save(AuthErrorCluster cluster);

    Page<AuthErrorCluster> findAll(Pageable pageable);

    Optional<AuthErrorCluster> findById(Long id);

    /**
     * clusterKey 기준으로 클러스터를 동시성 안전하게 확보하고 clusterId를 반환한다.
     */
    Long getOrCreateIdByClusterKey(String clusterKey, OffsetDateTime now);

    /**
     * cluster_item이 실제로 삽입된 경우에만 total_count를 +1 한다 (중복/레이스 완전 차단).
     *
     * @return true if item was inserted and count was incremented, false if item already existed.
     */
    boolean insertItemAndIncrementIfInserted(Long clusterId, Long authErrorId, OffsetDateTime now);

    /**
     * Touch cluster updated_at.
     */
    void touch(Long clusterId, OffsetDateTime now);
}
