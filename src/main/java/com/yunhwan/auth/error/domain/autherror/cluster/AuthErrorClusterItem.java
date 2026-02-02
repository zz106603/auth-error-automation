package com.yunhwan.auth.error.domain.autherror.cluster;

import jakarta.persistence.*;
import lombok.*;

import java.io.Serializable;
import java.time.OffsetDateTime;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
@Entity
@Table(name = "auth_error_cluster_item")
@IdClass(AuthErrorClusterItem.PK.class)
public class AuthErrorClusterItem {

    @Id
    @Column(name = "cluster_id", nullable = false)
    private Long clusterId;

    @Id
    @Column(name = "auth_error_id", nullable = false)
    private Long authErrorId;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    public static AuthErrorClusterItem of(Long clusterId, Long authErrorId, OffsetDateTime now) {
        return AuthErrorClusterItem.builder()
                .clusterId(clusterId)
                .authErrorId(authErrorId)
                .createdAt(now)
                .build();
    }

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PK implements Serializable {
        private Long clusterId;
        private Long authErrorId;
    }
}
