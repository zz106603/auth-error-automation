package com.yunhwan.auth.error.usecase.autherror.analysis;

import com.yunhwan.auth.error.common.exception.AuthErrorNotFoundException;
import com.yunhwan.auth.error.domain.autherror.AuthError;
import com.yunhwan.auth.error.domain.autherror.analysis.AuthErrorAnalysisResult;
import com.yunhwan.auth.error.infra.logging.AuthErrorEventLogger;
import com.yunhwan.auth.error.usecase.autherror.port.AuthErrorAnalysisResultStore;
import com.yunhwan.auth.error.usecase.autherror.port.AuthErrorAnalyzer;
import com.yunhwan.auth.error.usecase.autherror.port.AuthErrorStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthErrorAnalysisService {

    private static final String ANALYSIS_VERSION = "v1-stub";
    private static final String MODEL = "stub-rules";

    private final AuthErrorStore authErrorStore;
    private final AuthErrorAnalysisResultStore resultStore;
    private final AuthErrorAnalyzer analyzer;
    private final AuthErrorEventLogger eventLogger;

    @Transactional
    public void analyzeAndSave(Long authErrorId) {
        AuthError authError = authErrorStore.findById(authErrorId)
                .orElseThrow(() -> new AuthErrorNotFoundException(authErrorId));

        AuthErrorAnalyzer.AnalysisInput input = new AuthErrorAnalyzer.AnalysisInput(
                authError.getId(),
                authError.getHttpStatus(),
                authError.getExceptionClass(),
                authError.getExceptionMessage(),
                authError.getRootCauseClass(),
                authError.getRootCauseMessage(),
                authError.getStacktrace(),
                authError.getStackHash(),
                authError.getRequestUri(),
                authError.getHttpMethod()
        );

        AuthErrorAnalyzer.AnalysisResult out = analyzer.analyze(input);

        AuthErrorAnalysisResult row = AuthErrorAnalysisResult.builder()
                .authErrorId(authError.getId())
                .analysisVersion(ANALYSIS_VERSION)
                .model(MODEL)
                .category(out.category())
                .severity(out.severity())
                .summary(out.summary())
                .suggestedAction(out.suggestedAction())
                .confidence(out.confidence())
                .build();

        AuthErrorAnalysisResult saved = resultStore.save(row);

        // 분석 완료 이벤트 로그
        eventLogger.analysisCompleted(authError, saved);
    }
}