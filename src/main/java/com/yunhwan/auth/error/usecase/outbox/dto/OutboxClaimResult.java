package com.yunhwan.auth.error.usecase.outbox.dto;

import com.yunhwan.auth.error.domain.outbox.OutboxMessage;

import java.util.List;

public record OutboxClaimResult(String owner, List<OutboxMessage> claimed) {
}
