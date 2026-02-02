package com.yunhwan.auth.error.domain.autherror.cluster;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.OffsetDateTime;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "auth_error_cluster_decision_apply",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_cluster_decision_apply", columnNames = {"decision_id", "auth_error_id"})
        },
        indexes = {
                @Index(name = "ix_cluster_decision_apply_decision_id", columnList = "decision_id")
        }
)
public class AuthErrorClusterDecisionApply {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "decision_id", nullable = false)
    private Long decisionId;

    @Column(name = "auth_error_id", nullable = false)
    private Long authErrorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, length = 20)
    private ClusterApplyOutcome outcome;

    @Column(name = "message")
    private String message;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    public static AuthErrorClusterDecisionApply of(Long decisionId, Long authErrorId, ClusterApplyOutcome outcome, String message) {
        return AuthErrorClusterDecisionApply.builder()
                .decisionId(decisionId)
                .authErrorId(authErrorId)
                .outcome(outcome)
                .message(message)
                .build();
    }
}
