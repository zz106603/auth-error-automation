package com.yunhwan.auth.error.usecase.autherror;

import com.yunhwan.auth.error.app.api.auth.dto.ApplyAnalysisDecisionCommand;
import com.yunhwan.auth.error.app.api.auth.dto.ApplyAnalysisDecisionResult;

public interface AuthErrorDecisionApplier {
    ApplyAnalysisDecisionResult apply(ApplyAnalysisDecisionCommand cmd);
}
