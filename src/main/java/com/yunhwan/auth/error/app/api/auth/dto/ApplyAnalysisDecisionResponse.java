package com.yunhwan.auth.error.app.api.auth.dto;

import com.yunhwan.auth.error.domain.autherror.AuthErrorStatus;

public record ApplyAnalysisDecisionResponse(
        Long authErrorId,
        AuthErrorStatus status
) {}
