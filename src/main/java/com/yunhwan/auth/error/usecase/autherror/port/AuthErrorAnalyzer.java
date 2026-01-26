package com.yunhwan.auth.error.usecase.autherror.port;

import java.math.BigDecimal;

public interface AuthErrorAnalyzer {

    AnalysisResult analyze(AnalysisInput input);

    record AnalysisInput(
            Long authErrorId,
            Integer httpStatus,
            String exceptionClass,
            String exceptionMessage,
            String rootCauseClass,
            String rootCauseMessage,
            String stacktrace,
            String stackHash,
            String requestUri,
            String httpMethod
    ) {}

    record AnalysisResult(
            String category,
            String severity,
            String summary,
            String suggestedAction,
            BigDecimal confidence
    ) {}
}
