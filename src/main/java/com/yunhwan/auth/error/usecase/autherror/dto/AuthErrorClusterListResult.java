package com.yunhwan.auth.error.usecase.autherror.dto;

import java.util.List;

public record AuthErrorClusterListResult(
        List<AuthErrorClusterListItem> items,
        int page,
        int size,
        long totalElements,
        int totalPages
) {}
