package com.yunhwan.auth.error.usecase.autherror.port;

import com.yunhwan.auth.error.domain.autherror.cluster.AuthErrorClusterDecisionApply;

public interface AuthErrorClusterDecisionApplyStore {
    boolean exists(Long decisionId, Long authErrorId);
    AuthErrorClusterDecisionApply save(AuthErrorClusterDecisionApply apply);
}
