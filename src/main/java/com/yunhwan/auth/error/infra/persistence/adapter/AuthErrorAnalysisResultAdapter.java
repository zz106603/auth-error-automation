package com.yunhwan.auth.error.infra.persistence.adapter;

import com.yunhwan.auth.error.domain.autherror.analysis.AuthErrorAnalysisResult;
import com.yunhwan.auth.error.infra.persistence.jpa.AuthErrorAnalysisResultJpaRepository;
import com.yunhwan.auth.error.usecase.autherror.port.AuthErrorAnalysisResultStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
@Transactional
public class AuthErrorAnalysisResultAdapter implements AuthErrorAnalysisResultStore {

    private final AuthErrorAnalysisResultJpaRepository repo;

    @Override
    public AuthErrorAnalysisResult save(AuthErrorAnalysisResult authErrorAnalysisResult) {
        return repo.save(authErrorAnalysisResult);
    }
}
