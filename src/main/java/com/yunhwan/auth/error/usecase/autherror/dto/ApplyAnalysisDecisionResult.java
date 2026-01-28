package com.yunhwan.auth.error.usecase.autherror.dto;

import com.yunhwan.auth.error.domain.autherror.AuthErrorStatus;

public record ApplyAnalysisDecisionResult(
        Long authErrorId,
        AuthErrorStatus status
) {}
