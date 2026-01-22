package com.yunhwan.auth.error.usecase.autherror.dto;

import java.time.OffsetDateTime;

public record AuthErrorAnalysisRequestedPayload(
        Long authErrorId,
        String requestId,
        OffsetDateTime occurredAt,
        OffsetDateTime requestedAt
) {}
