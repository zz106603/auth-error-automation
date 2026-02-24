package com.yunhwan.auth.error.infra.metrics;

public final class MetricTags {

    private MetricTags() {}

    // 재시도 횟수는 1/2/3+만 유지(저카디널리티)
    public static String retryBucket(int retryCount) {
        if (retryCount <= 1) return MetricsConfig.RETRY_BUCKET_1;
        if (retryCount == 2) return MetricsConfig.RETRY_BUCKET_2;
        return MetricsConfig.RETRY_BUCKET_3PLUS;
    }
}
