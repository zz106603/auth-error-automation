package com.yunhwan.auth.error.autherror.repository;

import com.yunhwan.auth.error.domain.auth.AuthError;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AuthErrorRepository extends JpaRepository<AuthError, Long> {

    Optional<AuthError> findByDedupKey(String dedupKey);
}
