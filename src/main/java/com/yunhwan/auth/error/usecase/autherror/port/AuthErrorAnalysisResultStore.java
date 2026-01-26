package com.yunhwan.auth.error.usecase.autherror.port;

import com.yunhwan.auth.error.domain.autherror.analysis.AuthErrorAnalysisResult;

public interface AuthErrorAnalysisResultStore {
    AuthErrorAnalysisResult save(AuthErrorAnalysisResult result);
}
