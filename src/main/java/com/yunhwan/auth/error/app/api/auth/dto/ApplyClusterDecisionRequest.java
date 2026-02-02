package com.yunhwan.auth.error.app.api.auth.dto;

import com.yunhwan.auth.error.usecase.autherror.dto.DecisionActor;
import com.yunhwan.auth.error.usecase.autherror.dto.DecisionType;

public record ApplyClusterDecisionRequest(
        String idempotencyKey,
        DecisionType decisionType,
        String note,
        DecisionActor decidedBy
) {}
