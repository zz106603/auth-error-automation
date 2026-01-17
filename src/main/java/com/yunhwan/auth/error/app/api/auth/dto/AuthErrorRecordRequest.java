package com.yunhwan.auth.error.app.api.auth.dto;

import java.time.OffsetDateTime;

/**
 * AuthError 적재용 API 요청 DTO (최소 필드)
 * - requestId: 추적/멱등키에 사용
 * - occurredAt: payload 최소 계약에 포함
 *
 * 나머지 필드는 AuthError 도메인에 맞게 필요하면 추가
 */
public record AuthErrorRecordRequest(
        String requestId,
        OffsetDateTime occurredAt
) {}
