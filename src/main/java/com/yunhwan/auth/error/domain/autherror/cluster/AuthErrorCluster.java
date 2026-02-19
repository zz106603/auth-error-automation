package com.yunhwan.auth.error.domain.autherror.cluster;

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
        name = "auth_error_cluster",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_auth_error_cluster_cluster_key", columnNames = "cluster_key")
        },
        indexes = {
                @Index(name = "ix_auth_error_cluster_last_seen", columnList = "last_seen_at")
        }
)
public class AuthErrorCluster {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cluster_key", nullable = false, length = 64)
    private String clusterKey; // stack_hash

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private AuthErrorClusterStatus status;

    @Column(name = "title", length = 200)
    private String title;

    @Column(name = "summary")
    private String summary;

    @Column(name = "total_count", nullable = false)
    private long totalCount;

    @Column(name = "first_seen_at")
    private OffsetDateTime firstSeenAt;

    @Column(name = "last_seen_at")
    private OffsetDateTime lastSeenAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public static AuthErrorCluster open(String clusterKey, OffsetDateTime now) {
        return AuthErrorCluster.builder()
                .clusterKey(clusterKey)
                .status(AuthErrorClusterStatus.OPEN)
                .totalCount(0)
                .firstSeenAt(now)
                .lastSeenAt(now)
                .build();
    }

    public void changeStatus(AuthErrorClusterStatus status) {
        this.status = status;
    }
}
