package com.yunhwan.auth.error.app.api.auth.dto;

public record AuthErrorRecordResponse(
        long authErrorId,
        long outboxId
) {}
