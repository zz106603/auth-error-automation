package com.yunhwan.auth.error.usecase.autherror;

import com.yunhwan.auth.error.usecase.autherror.dto.ApplyAnalysisDecisionCommand;
import com.yunhwan.auth.error.usecase.autherror.dto.ApplyAnalysisDecisionResult;

public interface AuthErrorDecisionApplier {
    ApplyAnalysisDecisionResult apply(ApplyAnalysisDecisionCommand cmd);
}
