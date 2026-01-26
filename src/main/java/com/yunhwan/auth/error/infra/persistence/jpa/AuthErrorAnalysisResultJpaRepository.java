package com.yunhwan.auth.error.infra.persistence.jpa;

import com.yunhwan.auth.error.domain.autherror.analysis.AuthErrorAnalysisResult;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuthErrorAnalysisResultJpaRepository extends JpaRepository<AuthErrorAnalysisResult, Long> {
}
