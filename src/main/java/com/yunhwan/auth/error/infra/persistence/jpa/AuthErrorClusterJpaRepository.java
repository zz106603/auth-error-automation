package com.yunhwan.auth.error.infra.persistence.jpa;

import com.yunhwan.auth.error.domain.autherror.cluster.AuthErrorCluster;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Optional;

public interface AuthErrorClusterJpaRepository extends JpaRepository<AuthErrorCluster, Long> {
    Optional<AuthErrorCluster> findByClusterKey(String clusterKey);

    @Query(value = """
            insert into auth_error_cluster (cluster_key, total_count, created_at, updated_at)
            values (:clusterKey, 0, :now, :now)
            on conflict (cluster_key)
            do update set updated_at = excluded.updated_at
            returning id
            """, nativeQuery = true)
    Long upsertAndGetId(@Param("clusterKey") String clusterKey, @Param("now") OffsetDateTime now);

    @Modifying
    @Query(value = """
            with ins as (
                insert into auth_error_cluster_item (cluster_id, auth_error_id, created_at)
                values (:clusterId, :authErrorId, :now)
                on conflict (cluster_id, auth_error_id) do nothing
                returning 1
            )
            update auth_error_cluster c
               set total_count = c.total_count + 1,
                   updated_at = :now
             where c.id = :clusterId
               and exists (select 1 from ins)
            """, nativeQuery = true)
    int insertItemAndIncrementIfInserted(@Param("clusterId") Long clusterId,
                                         @Param("authErrorId") Long authErrorId,
                                         @Param("now") OffsetDateTime now);

    @Modifying
    @Query(value = """
            update auth_error_cluster
               set updated_at = :now
             where id = :clusterId
            """, nativeQuery = true)
    int touch(@Param("clusterId") Long clusterId, @Param("now") OffsetDateTime now);
}
