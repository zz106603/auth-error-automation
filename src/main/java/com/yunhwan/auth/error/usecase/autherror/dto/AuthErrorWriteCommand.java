package com.yunhwan.auth.error.usecase.autherror.dto;

import java.time.OffsetDateTime;

public record AuthErrorWriteCommand(
        String requestId,
        OffsetDateTime occurredAt,

        Integer httpStatus,

        String httpMethod,
        String requestUri,
        String clientIp,
        String userAgent,
        String userId,
        String sessionId,

        String exceptionClass,
        String exceptionMessage,
        String rootCauseClass,
        String rootCauseMessage,
        String stacktrace
) { }
