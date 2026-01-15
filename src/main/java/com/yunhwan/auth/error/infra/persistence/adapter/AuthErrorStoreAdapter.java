package com.yunhwan.auth.error.infra.persistence.adapter;

import com.yunhwan.auth.error.domain.autherror.AuthError;
import com.yunhwan.auth.error.infra.persistence.jpa.AuthErrorJpaRepository;
import com.yunhwan.auth.error.usecase.autherror.port.AuthErrorStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Repository
@RequiredArgsConstructor
@Transactional
public class AuthErrorStoreAdapter implements AuthErrorStore {

    private final AuthErrorJpaRepository repo;

    @Override
    public AuthError save(AuthError authError) {
        return repo.save(authError);
    }

    @Override
    public Optional<AuthError> findById(Long id) {
        return repo.findById(id);
    }

    @Override
    public Optional<AuthError> findByDedupKey(String dedupKey) {
        return repo.findByDedupKey(dedupKey);
    }
}
