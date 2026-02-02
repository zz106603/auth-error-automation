package com.yunhwan.auth.error.usecase.autherror.cluster;

import com.yunhwan.auth.error.usecase.autherror.dto.AuthErrorClusterListResult;
import org.springframework.data.domain.Pageable;

public interface AuthErrorClusterQueryService {
    AuthErrorClusterListResult list(Pageable pageable);
}
