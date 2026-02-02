package com.yunhwan.auth.error.usecase.autherror.port;

import com.yunhwan.auth.error.domain.autherror.cluster.AuthErrorClusterItem;

import java.util.List;

public interface AuthErrorClusterItemStore {
    boolean existsById(AuthErrorClusterItem.PK pk);

    AuthErrorClusterItem save(AuthErrorClusterItem item);

    List<Long> findAuthErrorIdsByClusterId(Long clusterId);
}
