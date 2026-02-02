package com.yunhwan.auth.error.usecase.autherror.cluster;

import com.yunhwan.auth.error.usecase.autherror.dto.ApplyClusterDecisionCommand;
import com.yunhwan.auth.error.usecase.autherror.dto.ApplyClusterDecisionResult;

public interface AuthErrorClusterDecisionApplier {
    ApplyClusterDecisionResult apply(ApplyClusterDecisionCommand cmd);
}
