package com.yunhwan.auth.error.usecase.autherror.dto;

import java.time.OffsetDateTime;

public record AuthErrorRecordedPayload(
        Long authErrorId,
        String requestId,
        OffsetDateTime occurredAt
) {}
