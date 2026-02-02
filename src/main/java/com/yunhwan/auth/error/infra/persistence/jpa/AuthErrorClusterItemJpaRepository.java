package com.yunhwan.auth.error.infra.persistence.jpa;

import com.yunhwan.auth.error.domain.autherror.cluster.AuthErrorClusterItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface AuthErrorClusterItemJpaRepository extends JpaRepository<AuthErrorClusterItem, AuthErrorClusterItem.PK> {

    @Query("select i.authErrorId from AuthErrorClusterItem i where i.clusterId = :clusterId")
    List<Long> findAuthErrorIdsByClusterId(Long clusterId);

}
