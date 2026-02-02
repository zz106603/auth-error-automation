package com.yunhwan.auth.error.usecase.autherror.dto;

import com.yunhwan.auth.error.domain.autherror.cluster.AuthErrorCluster;
import com.yunhwan.auth.error.domain.autherror.cluster.AuthErrorClusterStatus;

import java.time.OffsetDateTime;

public record AuthErrorClusterListItem(
        Long clusterId,
        String clusterKey,
        AuthErrorClusterStatus status,
        String title,
        long totalCount,
        OffsetDateTime firstSeenAt,
        OffsetDateTime lastSeenAt
) {
    public static AuthErrorClusterListItem from(AuthErrorCluster c) {
        return new AuthErrorClusterListItem(
                c.getId(),
                c.getClusterKey(),
                c.getStatus(),
                c.getTitle(),
                c.getTotalCount(),
                c.getFirstSeenAt(),
                c.getLastSeenAt()
        );
    }
}
