package com.yunhwan.auth.error.domain.autherror.cluster;

import com.yunhwan.auth.error.usecase.autherror.dto.DecisionActor;
import com.yunhwan.auth.error.usecase.autherror.dto.DecisionType;
import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.OffsetDateTime;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "auth_error_cluster_decision",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_cluster_decision_idem", columnNames = "idempotency_key")
        },
        indexes = {
                @Index(name = "ix_cluster_decision_cluster_id_created_at", columnList = "cluster_id,created_at")
        }
)
public class AuthErrorClusterDecision {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cluster_id", nullable = false)
    private Long clusterId;

    @Column(name = "idempotency_key", nullable = false, length = 100)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "decision_type", nullable = false, length = 30)
    private DecisionType decisionType;

    @Column(name = "note")
    private String note;

    @Enumerated(EnumType.STRING)
    @Column(name = "decided_by", nullable = false, length = 30)
    private DecisionActor decidedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private AuthErrorClusterDecisionStatus status;

    @Column(name = "total_targets", nullable = false)
    private int totalTargets;

    @Column(name = "applied_count", nullable = false)
    private int appliedCount;

    @Column(name = "skipped_count", nullable = false)
    private int skippedCount;

    @Column(name = "failed_count", nullable = false)
    private int failedCount;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public static AuthErrorClusterDecision open(Long clusterId, String idempotencyKey, DecisionType decisionType, String note, DecisionActor decidedBy) {
        return AuthErrorClusterDecision.builder()
                .clusterId(clusterId)
                .idempotencyKey(idempotencyKey)
                .decisionType(decisionType)
                .note(note)
                .decidedBy(decidedBy)
                .status(AuthErrorClusterDecisionStatus.APPLIED)
                .totalTargets(0)
                .appliedCount(0)
                .skippedCount(0)
                .failedCount(0)
                .build();
    }

    public void recordResult(int total, int applied, int skipped, int failed) {
        this.totalTargets = total;
        this.appliedCount = applied;
        this.skippedCount = skipped;
        this.failedCount = failed;
        if (failed > 0 && applied > 0) this.status = AuthErrorClusterDecisionStatus.PARTIAL;
        else if (failed > 0) this.status = AuthErrorClusterDecisionStatus.FAILED;
        else this.status = AuthErrorClusterDecisionStatus.APPLIED;
    }
}
