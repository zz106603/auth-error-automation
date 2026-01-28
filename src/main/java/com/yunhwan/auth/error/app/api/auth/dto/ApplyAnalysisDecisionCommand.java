package com.yunhwan.auth.error.app.api.auth.dto;

import com.yunhwan.auth.error.usecase.autherror.dto.DecisionActor;
import com.yunhwan.auth.error.usecase.autherror.dto.DecisionType;

public record ApplyAnalysisDecisionCommand(
        Long authErrorId,
        DecisionType decisionType,
        String note,
        DecisionActor decidedBy
) {

    public static ApplyAnalysisDecisionCommand from(Long authErrorId, ApplyAnalysisDecisionRequest req) {
        return new ApplyAnalysisDecisionCommand(authErrorId, req.decisionType(), req.note(), req.decidedBy());
    }

}
