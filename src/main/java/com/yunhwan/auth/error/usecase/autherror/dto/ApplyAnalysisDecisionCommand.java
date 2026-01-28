package com.yunhwan.auth.error.usecase.autherror.dto;

public record ApplyAnalysisDecisionCommand(
        Long authErrorId,
        DecisionType decisionType,
        String note,
        DecisionActor decidedBy
) {}
