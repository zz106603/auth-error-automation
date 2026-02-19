package com.yunhwan.auth.error.usecase.autherror.cluster;

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

        // 1) cluster 확보 (동시성 안전)
        Long clusterId = authErrorClusterStore.getOrCreateIdByClusterKey(stackHash, now);

        // 2) item 삽입 성공시에만 카운트 증가 (CTE로 강제)
        boolean inserted = authErrorClusterStore.insertItemAndIncrementIfInserted(clusterId, authErrorId, now);

        log.info("[ClusterLink] linked. authErrorId={}, clusterId={}, key={}, flag={}", authErrorId, clusterId, stackHash, inserted);
    }
}

