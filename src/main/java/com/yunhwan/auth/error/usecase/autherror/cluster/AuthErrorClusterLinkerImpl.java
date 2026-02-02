package com.yunhwan.auth.error.usecase.autherror.cluster;

import com.yunhwan.auth.error.domain.autherror.cluster.AuthErrorCluster;
import com.yunhwan.auth.error.domain.autherror.cluster.AuthErrorClusterItem;
import com.yunhwan.auth.error.usecase.autherror.port.AuthErrorClusterItemStore;
import com.yunhwan.auth.error.usecase.autherror.port.AuthErrorClusterStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthErrorClusterLinkerImpl implements AuthErrorClusterLinker {

    private final AuthErrorClusterStore authErrorClusterStore;
    private final AuthErrorClusterItemStore authErrorClusterItemStore;

    @Override
    @Transactional
    public void link(Long authErrorId, String stackHash) {
        if (stackHash == null || stackHash.isBlank()) {
            log.info("[ClusterLink] skip: stackHash is blank. authErrorId={}", authErrorId);
            return;
        }

        OffsetDateTime now = OffsetDateTime.now();

        AuthErrorCluster cluster = authErrorClusterStore.findByClusterKey(stackHash)
                .orElseGet(() -> authErrorClusterStore.save(AuthErrorCluster.open(stackHash, now)));

        // 통계는 "신규 link"일 때만 올려야 중복에 안전함
        AuthErrorClusterItem.PK pk = new AuthErrorClusterItem.PK(cluster.getId(), authErrorId);
        if (authErrorClusterItemStore.existsById(pk)) {
            cluster.touch(now);
            // 중복 link면 count는 올리지 않음
            return;
        }

        authErrorClusterItemStore.save(AuthErrorClusterItem.of(cluster.getId(), authErrorId, now));

        cluster.touch(now);
        cluster.incrementCount();
        // cluster는 dirty-check로 업데이트됨

        log.info("[ClusterLink] linked. authErrorId={}, clusterId={}, key={}", authErrorId, cluster.getId(), stackHash);
    }
}

