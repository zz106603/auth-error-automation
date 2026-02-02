package com.yunhwan.auth.error.usecase.autherror.dto;

public record ApplyClusterDecisionResult(
        Long clusterId,
        int totalTargets,
        int appliedCount,
        int skippedCount,
        int failedCount
) {}
