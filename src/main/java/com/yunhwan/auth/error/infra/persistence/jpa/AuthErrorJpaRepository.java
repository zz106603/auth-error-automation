package com.yunhwan.auth.error.infra.persistence.jpa;

import com.yunhwan.auth.error.domain.autherror.AuthError;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AuthErrorJpaRepository extends JpaRepository<AuthError, Long> {

    Optional<AuthError> findByDedupKey(String dedupKey);
}
