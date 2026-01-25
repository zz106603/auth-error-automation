package com.yunhwan.auth.error.app.api.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;

/**
 * AuthError 적재용 API 요청 DTO
 * - requestId: 추적/멱등키에 사용
 * - occurredAt: payload 최소 계약에 포함
 */
public record AuthErrorRecordRequest(
        @NotBlank String requestId,
        @NotNull OffsetDateTime occurredAt,

        @NotNull Integer httpStatus,

        @NotBlank String exceptionClass,
        @NotBlank String stacktrace,

        // optional context
        String httpMethod,
        String requestUri,
        String clientIp,
        String userAgent,
        String userId,
        String sessionId,

        // optional exception detail
        String exceptionMessage,
        String rootCauseClass,
        String rootCauseMessage
) {}
