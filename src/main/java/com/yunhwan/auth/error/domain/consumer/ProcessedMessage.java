package com.yunhwan.auth.error.domain.consumer;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "processed_message")
public class ProcessedMessage {

    @Id
    @Column(name = "outbox_id")
    private Long outboxId;

    @Column(name = "processed_at", nullable = false)
    private OffsetDateTime processedAt;

}
