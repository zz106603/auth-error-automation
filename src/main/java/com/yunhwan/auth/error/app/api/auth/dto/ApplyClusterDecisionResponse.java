package com.yunhwan.auth.error.app.api.auth.dto;

public record ApplyClusterDecisionResponse(
        Long clusterId,
        int totalTargets,
        int appliedCount,
        int skippedCount,
        int failedCount
) {}
