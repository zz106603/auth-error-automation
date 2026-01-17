package com.yunhwan.auth.error.app.autherror;


import com.yunhwan.auth.error.app.api.auth.dto.AuthErrorRecordRequest;
import com.yunhwan.auth.error.app.api.auth.dto.AuthErrorRecordResponse;
import com.yunhwan.auth.error.domain.autherror.AuthError;
import com.yunhwan.auth.error.usecase.autherror.AuthErrorWriter;
import com.yunhwan.auth.error.usecase.autherror.dto.AuthErrorWriteResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthErrorFacade {

    private final AuthErrorWriter authErrorWriter;

    public AuthErrorRecordResponse record(AuthErrorRecordRequest req) {

        AuthError authError = AuthError.builder()
                .requestId(req.requestId())
                .occurredAt(req.occurredAt())
                .build();

        AuthErrorWriteResult result = authErrorWriter.record(authError);
        return new AuthErrorRecordResponse(result.authErrorId(), result.outboxId());
    }
}
