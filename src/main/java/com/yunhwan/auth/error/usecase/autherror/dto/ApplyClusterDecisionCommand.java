package com.yunhwan.auth.error.usecase.autherror.dto;

import com.yunhwan.auth.error.app.api.auth.dto.ApplyClusterDecisionRequest;

public record ApplyClusterDecisionCommand(
        String idempotencyKey,
        Long clusterId,
        DecisionType decisionType,
        String note,
        DecisionActor decidedBy
) {
    public static ApplyClusterDecisionCommand from(Long clusterId, ApplyClusterDecisionRequest req) {
        return new ApplyClusterDecisionCommand(req.idempotencyKey(), clusterId, req.decisionType(), req.note(), req.decidedBy());
    }
}
