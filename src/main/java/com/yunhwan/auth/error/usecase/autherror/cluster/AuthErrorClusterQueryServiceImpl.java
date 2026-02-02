package com.yunhwan.auth.error.usecase.autherror.cluster;

import com.yunhwan.auth.error.domain.autherror.cluster.AuthErrorCluster;
import com.yunhwan.auth.error.usecase.autherror.dto.AuthErrorClusterListItem;
import com.yunhwan.auth.error.usecase.autherror.dto.AuthErrorClusterListResult;
import com.yunhwan.auth.error.usecase.autherror.port.AuthErrorClusterStore;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AuthErrorClusterQueryServiceImpl implements AuthErrorClusterQueryService {

    private final AuthErrorClusterStore clusterStore;

    @Override
    @Transactional(readOnly = true)
    public AuthErrorClusterListResult list(Pageable pageable) {
        Page<AuthErrorCluster> page = clusterStore.findAll(pageable);

        List<AuthErrorClusterListItem> items = page.getContent().stream()
                .map(AuthErrorClusterListItem::from)
                .toList();

        return new AuthErrorClusterListResult(
                items,
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages()
        );
    }
}
